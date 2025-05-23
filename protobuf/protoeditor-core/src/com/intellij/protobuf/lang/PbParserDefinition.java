/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.protobuf.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.protobuf.lang.lexer.ProtoLexer;
import com.intellij.protobuf.lang.parser.PbParser;
import com.intellij.protobuf.lang.psi.PbTextElementType;
import com.intellij.protobuf.lang.psi.PbTextTypes;
import com.intellij.protobuf.lang.psi.PbTypes;
import com.intellij.protobuf.lang.psi.ProtoTokenTypes;
import com.intellij.protobuf.lang.psi.impl.PbFileImpl;
import com.intellij.protobuf.lang.stub.type.PbFileElementType;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public class PbParserDefinition implements ParserDefinition {

  private static final IFileElementType FILE = new PbFileElementType(PbLanguage.INSTANCE);

  private static class PbParserTokenSets {
    private static final TokenSet WHITE_SPACE = TokenSet.WHITE_SPACE;
    private static final TokenSet COMMENTS =
      TokenSet.create(ProtoTokenTypes.LINE_COMMENT, ProtoTokenTypes.BLOCK_COMMENT);
    private static final TokenSet STRINGS = TokenSet.create(ProtoTokenTypes.STRING_LITERAL);
  }

  public PbParserDefinition() {}

  @Override
  public @NotNull Lexer createLexer(Project project) {
    return ProtoLexer.forProtobufWithKeywords();
  }

  @Override
  public @NotNull PsiParser createParser(final Project project) {
    return new PbParser();
  }

  @Override
  public @NotNull TokenSet getWhitespaceTokens() {
    return PbParserTokenSets.WHITE_SPACE;
  }

  @Override
  public @NotNull TokenSet getCommentTokens() {
    return PbParserTokenSets.COMMENTS;
  }

  @Override
  public @NotNull TokenSet getStringLiteralElements() {
    return PbParserTokenSets.STRINGS;
  }

  @Override
  public @NotNull IFileElementType getFileNodeType() {
    return FILE;
  }

  @Override
  public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
    return new PbFileImpl(viewProvider);
  }

  @Override
  public @NotNull SpaceRequirements spaceExistenceTypeBetweenTokens(ASTNode left, ASTNode right) {
    return SpaceRequirements.MAY;
  }

  @Override
  public @NotNull PsiElement createElement(ASTNode node) {
    if (node instanceof PbTextElementType) {
      return PbTextTypes.Factory.createElement(node);
    }
    return PbTypes.Factory.createElement(node);
  }
}
