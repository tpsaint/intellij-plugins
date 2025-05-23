/*
 * Copyright 2000-2005 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.perforce.application;

import com.intellij.ide.IdeBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.annotate.AnnotationProvider;
import com.intellij.openapi.vcs.annotate.AnnotationsWriteableFilesVfsListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.history.VcsHistoryProvider;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.merge.MergeProvider;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vcs.update.UpdateEnvironment;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.io.ReadOnlyAttributeUtil;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.perforce.PerforceBundle;
import org.jetbrains.idea.perforce.actions.ShelfUtils;
import org.jetbrains.idea.perforce.merge.PerforceMergeProvider;
import org.jetbrains.idea.perforce.operations.P4EditOperation;
import org.jetbrains.idea.perforce.operations.VcsOperation;
import org.jetbrains.idea.perforce.operations.VcsOperationLog;
import org.jetbrains.idea.perforce.perforce.P4File;
import org.jetbrains.idea.perforce.perforce.PerforceRunner;
import org.jetbrains.idea.perforce.perforce.PerforceSettings;
import org.jetbrains.idea.perforce.perforce.connections.P4Connection;
import org.jetbrains.idea.perforce.perforce.connections.PerforceConnectionManager;
import org.jetbrains.idea.perforce.perforce.connections.PerforceConnectionManagerI;
import org.jetbrains.idea.perforce.perforce.jobs.PerforceJob;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.intellij.platform.util.coroutines.CoroutineScopeKt.childScope;
import static com.intellij.util.concurrency.AppJavaExecutorUtil.awaitCancellationAndDispose;

public final class PerforceVcs extends AbstractVcs {
  public static final @NlsSafe String NAME = "Perforce";
  private static final VcsKey ourKey = createKey(NAME);

  private PerforceCheckinEnvironment myPerforceCheckinEnvironment;
  private PerforceUpdateEnvironment myPerforceUpdateEnvironment;
  private PerforceIntegrateEnvironment myPerforceIntegrateEnvironment;
  private RollbackEnvironment myPerforceRollbackEnvironment;
  private RollbackEnvironment myOfflineRollbackEnvironment;
  private PerforceCommittedChangesProvider myCommittedChangesProvider;

  private final MyEditFileProvider myMyEditFileProvider;
  private PerforceChangeProvider myChangeProvider;
  private ChangeProvider myOfflineChangeProvider;
  private PerforceVcsHistoryProvider myHistoryProvider;
  private PerforceAnnotationProvider myAnnotationProvider;
  private PerforceDiffProvider myDiffProvider;
  private PerforceTreeDiffProvider myTreeDiffProvider;

  private MergeProvider myMergeProvider;

  private final AtomicReference<CoroutineScope> myActiveScope = new AtomicReference<>();

  private final Set<VirtualFile> myAsyncEditFiles = new HashSet<>();

  private final Map<ConnectionKey, List<PerforceJob>> myDefaultAssociated = new HashMap<>();

  private final ReentrantReadWriteLock myP4Lock = new ReentrantReadWriteLock();

  public PerforceVcs(@NotNull Project project) {
    super(project, NAME);
    myMyEditFileProvider = new MyEditFileProvider();
  }

  @Override
  public @NotNull String getDisplayName() {
    return NAME;
  }

  @Override
  public @Nls @NotNull String getShortNameWithMnemonic() {
    return PerforceBundle.message("perforce.name.with.mnemonic");
  }

  @Override
  public boolean isCommitActionDisabled() {
    return !PerforceSettings.getSettings(myProject).ENABLED;
  }

  @Override
  public boolean isUpdateActionDisabled() {
    return !PerforceSettings.getSettings(myProject).ENABLED;
  }

  private @Nullable <T> T validProvider(T initialValue) {
    return getSettings().ENABLED ? initialValue : null;
  }

  @Override
  public boolean allowsRemoteCalls(@NotNull VirtualFile file) {
    return getSettings().ENABLED;
  }

  @Override
  public boolean isTrackingUnchangedContent() {
    return true;
  }

  @Override
  public @NotNull EditFileProvider getEditFileProvider() {
    return myMyEditFileProvider;
  }

  @Override
  public @NotNull PerforceCheckinEnvironment getCheckinEnvironment() {
    if (myPerforceCheckinEnvironment == null) {
      myPerforceCheckinEnvironment = new PerforceCheckinEnvironment(myProject, this);
    }
    return myPerforceCheckinEnvironment;
  }

  @Override
  public RollbackEnvironment getRollbackEnvironment() {
    if (PerforceSettings.getSettings(myProject).ENABLED) {
      if (myPerforceRollbackEnvironment == null) {
        myPerforceRollbackEnvironment = new PerforceRollbackEnvironment(myProject);
      }
      return myPerforceRollbackEnvironment;
    }
    else {
      if (myOfflineRollbackEnvironment == null) {
        myOfflineRollbackEnvironment = new PerforceOfflineRollbackEnvironment(myProject);
      }
      return myOfflineRollbackEnvironment;
    }
  }

  private void autoEditVFile(final VirtualFile[] vFiles) {
    ChangeListManager changeListManager = ChangeListManager.getInstance(myProject);
    // check whether it will be under any clientspec
    ApplicationManager.getApplication().runWriteAction(() -> {
      for (VirtualFile file : vFiles) {
        PerforceVFSListener.updateLastUnchangedContent(file, changeListManager);
        try {
          ReadOnlyAttributeUtil.setReadOnlyAttribute(file, false);
        }
        catch (IOException e) {
          // ignore - we'll get some message from 'p4 revert'
        }
      }
    });
    startAsyncEdit(vFiles);

    List<VcsOperation> operations = new ArrayList<>();
    for(VirtualFile vFile: vFiles) {
      LocalChangeList list = changeListManager.getChangeList(vFile);
      if (list == null) {
        list = changeListManager.getDefaultChangeList();
      }
      operations.add(new P4EditOperation(list.getName(), vFile, false));
    }
    VcsOperationLog.getInstance(myProject).queueOperations(operations, PerforceBundle.message("progress.title.perforce.edit"),
                                                           PerformInBackgroundOption.ALWAYS_BACKGROUND,
                                                           () -> refreshFiles(vFiles));
  }

  public void refreshFiles(VirtualFile @NotNull ... filesToRefresh) {
    List<VirtualFile> files = Arrays.asList(filesToRefresh);
    LocalFileSystem.getInstance()
      .refreshFiles(files, true, false, null);
    asyncEditCompleted(files);
    VcsDirtyScopeManager.getInstance(myProject).filesDirty(files, null);
  }

  public void startAsyncEdit(VirtualFile @NotNull ... vFiles) {
    synchronized (myAsyncEditFiles) {
      Collections.addAll(myAsyncEditFiles, vFiles);
    }
  }

  public Set<VirtualFile> getAsyncEditedFiles() {
    synchronized (myAsyncEditFiles) {
      return new HashSet<>(myAsyncEditFiles);
    }
  }

  public void asyncEditCompleted(@NotNull Collection<VirtualFile> files) {
    synchronized (myAsyncEditFiles) {
      myAsyncEditFiles.removeAll(files);
    }
  }

  public void asyncEditCompleted(@NotNull VirtualFile file) {
    synchronized (myAsyncEditFiles) {
      myAsyncEditFiles.remove(file);
    }
  }

  public static PerforceVcs getInstance(Project project) {
    return (PerforceVcs)ProjectLevelVcsManager.getInstance(project).findVcsByName(NAME);
  }

  @Override
  public @NotNull UpdateEnvironment getUpdateEnvironment() {
    if (myPerforceUpdateEnvironment == null) {
      myPerforceUpdateEnvironment = new PerforceUpdateEnvironment(myProject);
    }
    /*return validProvider(myPerforceUpdateEnvironment);*/
    return myPerforceUpdateEnvironment;
  }

  public PerforceSettings getSettings() {
    return PerforceSettings.getSettings(myProject);
  }

  public static @Nullable String getFileNameComplaint(P4File file) {
    String filename = file.getLocalPath();
    if (StringUtil.containsAnyChar(filename, "@#%*")) {
      return PerforceBundle.message("file.wildcards.restricted", filename);
    }
    return null;
  }

  private class MyEditFileProvider implements EditFileProvider {
    @Override
    public void editFiles(VirtualFile[] files) {
      autoEditVFile(files);
    }

    @Override
    public String getRequestText() {
      return PerforceBundle.message("confirmation.text.open.files.for.edit");
    }

  }

  @Override
  public boolean fileIsUnderVcs(FilePath filePath) {
    VirtualFile virtualFile = filePath.getVirtualFile();
    if (virtualFile == null) return false;

    try {
      if (virtualFile.isDirectory()) {
        // at the moment is not called, never. so doesn't care abt that..
        //if (PerforceConnectionManager.getInstance(myProject).isInitializingConnections()) return true;
        return PerforceManager.getInstance(getProject()).isUnderPerforceRoot(virtualFile);
      }
      else {
        final P4File p4File = P4File.create(virtualFile);

        return PerforceRunner.getInstance(myProject).have(p4File);
      }
    }
    catch (VcsException e) {
      return false;
    }
  }

  @Override
  public boolean fileExistsInVcs(FilePath filePath) {
    VirtualFile virtualFile = filePath.getVirtualFile();
    if (virtualFile == null) return false;

    final PerforceManager perforceManager = PerforceManager.getInstance(getProject());

    if (! getSettings().ENABLED) {
      return true;
    }

    if (virtualFile.isDirectory()) {
      try {
        return perforceManager.isUnderPerforceRoot(virtualFile);
      }
      catch (VcsException e) {
        return false;
      }
    }
    else {
      final FileStatus fileStatus = ChangeListManager.getInstance(myProject).getStatus(virtualFile);
      if (FileStatus.ADDED.equals(fileStatus)) {
        if (Boolean.TRUE.equals(virtualFile.getUserData(OpenedResultProcessor.BRANCHED_FILE))) {
          return true;
        }
      }
      return fileStatus != FileStatus.UNKNOWN && fileStatus != FileStatus.ADDED;
    }
  }

  @Override
  public VcsHistoryProvider getVcsHistoryProvider() {
    if (myHistoryProvider == null) {
      myHistoryProvider = new PerforceVcsHistoryProvider(this);
    }
    return validProvider(myHistoryProvider);
  }

  @Override
  public VcsHistoryProvider getVcsBlockHistoryProvider() {
    return getVcsHistoryProvider();
  }

  @Override
  public AnnotationProvider getAnnotationProvider() {
    if (myAnnotationProvider == null) {
      myAnnotationProvider = new PerforceAnnotationProvider(myProject);
    }
    return validProvider(myAnnotationProvider);
  }

  @Override
  public DiffProvider getDiffProvider() {
    if (myDiffProvider == null) {
      myDiffProvider = new PerforceDiffProvider(myProject);
    }
    return validProvider(myDiffProvider);
  }

  @Override
  public @NotNull ChangeProvider getChangeProvider() {
    if (getSettings().ENABLED) {
      return getOnlineChangeProvider();
    }
    else {
      if (myOfflineChangeProvider == null) {
        myOfflineChangeProvider = new PerforceOfflineChangeProvider(myProject);
      }
      return myOfflineChangeProvider;
    }
  }

  public PerforceChangeProvider getOnlineChangeProvider() {
    initChangeProvider();
    return myChangeProvider;
  }

  private void initChangeProvider() {
    if (myChangeProvider == null) {
      myChangeProvider = new PerforceChangeProvider(this);
    }
  }

  @Override
  public UpdateEnvironment getIntegrateEnvironment() {
    if (myPerforceIntegrateEnvironment == null) {
      myPerforceIntegrateEnvironment = new PerforceIntegrateEnvironment(myProject);
    }
    return validProvider(myPerforceIntegrateEnvironment);
  }

  @Override
  public void activate() {
    CoroutineScope globalScope = PerforceDisposable.getCoroutineScope(myProject);
    CoroutineScope activeScope = childScope(globalScope, "PerforceVcs", EmptyCoroutineContext.INSTANCE, true);

    Disposable disposable = Disposer.newDisposable();
    awaitCancellationAndDispose(activeScope, disposable);

    Disposer.register(disposable, PerforceVFSListener.createInstance(myProject, activeScope));

    PerforceManager.getInstance(myProject).startListening(disposable);
    ((PerforceConnectionManager)PerforceConnectionManager.getInstance(myProject)).startListening(disposable);
    PerforceNumberNameSynchronizer.getInstance(myProject).startListening(disposable);
    PerforceSettings.getSettings(myProject).ensureOfflineNotify();

    ReadonlyStatusIsVisibleActivationCheck.check(myProject, NAME);
    initChangeProvider();
    myChangeProvider.activate(disposable);
    VirtualFileManager.getInstance().addVirtualFileListener(new AnnotationsWriteableFilesVfsListener(myProject, getKey()), disposable);
  }

  @Override
  public void deactivate() {
    CoroutineScope oldScope = myActiveScope.getAndSet(null);
    if (oldScope != null) CoroutineScopeKt.cancel(oldScope, null);
  }

  @Override
  public @NotNull PerforceCommittedChangesProvider getCommittedChangesProvider() {
    if (myCommittedChangesProvider == null) {
      myCommittedChangesProvider = new PerforceCommittedChangesProvider(myProject);
    }
    return myCommittedChangesProvider;
  }

  public void runBackgroundTask(@NotNull @NlsContexts.ProgressTitle String title,
                                @NotNull PerformInBackgroundOption option,
                                @NotNull Runnable runnable, @Nullable Runnable edtCallback) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      runnable.run();
      if (edtCallback != null) {
        edtCallback.run();
      }
    } else {
      ProgressManager.getInstance().run(new Task.Backgroundable(myProject, title, false, option) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          runnable.run();
        }

        @Override
        public void onFinished() {
          if (edtCallback != null) {
            edtCallback.run();
          }
        }
      });

    }
  }

  @Override
  public @Nullable VcsRevisionNumber parseRevisionNumber(final String revisionNumberString) {
    long revision;
    try {
      revision = Long.parseLong(revisionNumberString);
    }
    catch (NumberFormatException ex) {
      return null;
    }
    return new PerforceOnlyRevisionNumber(revision);
  }

  @Override
  public String getRevisionPattern() {
    return ourIntegerPattern;
  }

  @Override
  public @NotNull MergeProvider getMergeProvider() {
    if (myMergeProvider == null) {
      myMergeProvider = new PerforceMergeProvider(myProject);
    }
    return myMergeProvider;
  }

  public Map<ConnectionKey, List<PerforceJob>> getDefaultAssociated() {
    synchronized (myDefaultAssociated) {
      return new HashMap<>(myDefaultAssociated);
    }
  }

  public void setDefaultAssociated(final Map<ConnectionKey, List<PerforceJob>> jobs) {
    synchronized (myDefaultAssociated) {
      myDefaultAssociated.clear();
      myDefaultAssociated.putAll(jobs);
    }
  }

  public void clearDefaultAssociated() {
    synchronized (myDefaultAssociated) {
      myDefaultAssociated.clear();
    }
  }

  @Override
  public boolean isVersionedDirectory(VirtualFile dir) {
    //return PerforceManager.getInstance(myProject).isUnderPerforceDefaultYes(dir);
    try {
      return PerforceManager.getInstance(myProject).isUnderPerforceRoot(dir);
    }
    catch (VcsException e) {
      return false;
    }
  }

  @Override
  public VcsExceptionsHotFixer getVcsExceptionsHotFixer() {
    return myProject.getService(PerforceExceptionsHotFixer.class);
  }

  public static VcsKey getKey() {
    return ourKey;
  }

  public Collection<Pair<P4Connection, Collection<VirtualFile>>> getRootsByConnections() throws VcsException {
    final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
    final PerforceConnectionManagerI connectionManager = PerforceConnectionManager.getInstance(myProject);
    final VirtualFile[] roots = vcsManager.getRootsUnderVcs(this);

    final MultiMap<ConnectionKey, VirtualFile> rootsByConnections = new MultiMap<>();
    final Map<ConnectionKey, P4Connection> connMap = new HashMap<>();
    for (VirtualFile root : roots) {
      final P4Connection connection = connectionManager.getConnectionForFile(root);
      if (connection != null) {
        PerforceManager.ensureValidClient(myProject, connection);
        final ConnectionKey key = connection.getConnectionKey();
        connMap.put(key, connection);
        rootsByConnections.putValue(key, root);
      }
    }

    final Collection<Pair<P4Connection, Collection<VirtualFile>>> result = new LinkedList<>();
    for (ConnectionKey key : rootsByConnections.keySet()) {
      final P4Connection connection = connMap.get(key);
      result.add(Pair.create(connection, rootsByConnections.get(key)));
    }

    return result;
  }

  @Override
  protected TreeDiffProvider getTreeDiffProviderImpl() {
    if (myTreeDiffProvider == null) {
      myTreeDiffProvider = new PerforceTreeDiffProvider(this);
    }
    return myTreeDiffProvider;
  }

  @Override
  public RemoteDifferenceStrategy getRemoteDifferenceStrategy() {
    return RemoteDifferenceStrategy.ASK_TREE_PROVIDER;
  }

  @Override
  public boolean allowsNestedRoots() {
    return true;
  }

  public static boolean revisionsSame(@NotNull VcsRevisionNumber number1, @NotNull VcsRevisionNumber number2) {
    final long revision1 = extractNumber(number1);
    final long revision2 = extractNumber(number2);
    return revision1 > 0 && revision1 == revision2;
  }

  private static long extractNumber(@NotNull VcsRevisionNumber number) {
    if (number instanceof VcsRevisionNumber.Long) {
      return ((VcsRevisionNumber.Long)number).getLongValue();
    }
    if (number instanceof PerforceVcsRevisionNumber) {
      return ((PerforceVcsRevisionNumber)number).getChangeNumber();
    }
    if (number instanceof PerforceOnlyRevisionNumber) {
      return ((PerforceOnlyRevisionNumber) number).getNumber();
    }
    return -1;
  }

  @Override
  public @NotNull ThreeState mayRemoveChangeList(@NotNull LocalChangeList list, boolean explicitly) {
    List<ShelvedChange> shelvedChanges = PerforceManager.getInstance(myProject).getShelf().getShelvedChanges(list);
    if (!shelvedChanges.isEmpty()) {
      if (!explicitly) {
        return ThreeState.NO;
      }

      String message = PerforceBundle.message("changelist.shelved.changes.delete", list.getName());
      int rc = Messages.showDialog(myProject, message, PerforceBundle.message("changelist.shelved.changes.found"),
                                   new String[]{PerforceBundle.message("shelf.browse.mnemonic"), IdeBundle.message("button.remove"), Messages.getCancelButton()}, 0,
                                   Messages.getQuestionIcon());
      if (rc == 1) {
        ShelfUtils.deleteFromShelf(shelvedChanges, myProject);
        return ThreeState.YES;
      }
      if (rc == 0) {
        ShelfUtils.browseShelf(myProject, shelvedChanges);
      }
      return ThreeState.NO;
    }

    return ThreeState.UNSURE;
  }

  public AccessToken readLockP4() {
    ReentrantReadWriteLock.ReadLock lock = myP4Lock.readLock();
    lock.lock();
    return new AccessToken() {
      @Override
      public void finish() {
        lock.unlock();
      }
    };
  }

  public AccessToken writeLockP4() {
    ReentrantReadWriteLock.WriteLock lock = myP4Lock.writeLock();
    lock.lock();
    return new AccessToken() {
      @Override
      public void finish() {
        lock.unlock();
      }
    };
  }

}
