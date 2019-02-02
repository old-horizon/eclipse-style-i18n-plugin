package com.github.old_horizon.idea;

import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.util.PsiTreeUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class LineModel {

    private static final String SPACE = " ";
    private static final String NON_NLS_FORMAT = "//$NON-NLS-%d$";
    private static final Pattern NON_NLS_PATTERN = Pattern.compile("//\\$NON-NLS-(\\d+)\\$");

    private final PsiJavaFile file;
    private final PsiDirectory directory;
    private final Project project;
    private final PsiDocumentManager documentManager;
    private final Document document;
    private final int lineEndOffset;
    private final List<PsiJavaToken> strings = new ArrayList<>();
    private final List<CommentFacade> comments = new ArrayList<>();

    LineModel(final PsiElement anchor) {
        file = (PsiJavaFile) anchor.getContainingFile();
        directory = file.getContainingDirectory();
        project = file.getProject();

        documentManager = PsiDocumentManager.getInstance(project);
        document = documentManager.getDocument(file);

        final int lineNumber = document.getLineNumber(anchor.getTextRange().getStartOffset());
        final int lineStartOffset = document.getLineStartOffset(lineNumber);
        lineEndOffset = document.getLineEndOffset(lineNumber);

        for (final Iterator<PsiElement> iterator = new LineElementIterator(file, lineStartOffset, lineEndOffset);
             iterator.hasNext(); ) {
            final PsiElement element = iterator.next();
            if (element instanceof PsiJavaToken) {
                final PsiJavaToken javaToken = (PsiJavaToken) element;
                if (javaToken.getTokenType() == JavaTokenType.STRING_LITERAL &&
                        PsiTreeUtil.getParentOfType(javaToken, PsiAnnotation.class) == null) {
                    strings.add(javaToken);
                }
            } else if (element instanceof PsiComment) {
                comments.add(new CommentFacade((PsiComment) element));
            }
        }
    }

    boolean needToExternalize(final PsiJavaToken javaToken) {
        final int index = strings.indexOf(javaToken);

        if (index == -1) {
            return false;
        }

        final int ordinal = index + 1;
        return comments.stream()
                .flatMap(comment -> comment.getNonNlsOrdinals().stream())
                .noneMatch(o -> o == ordinal);
    }

    void externalize(final String key, final PsiJavaToken javaToken) {
        final String value = StringUtil.unquoteString(javaToken.getText());

        createResourceBundleAccessorClass("Messages.java");
        writeProperty("messages.properties", key, value);
        // Invoke before changing line offset
        writeNonNls(javaToken);

        replaceElementByText(javaToken, String.format("Messages.getString(\"%s\")", key));
    }

    void ignore(final PsiJavaToken javaToken) {
        writeNonNls(javaToken);
    }

    private void createResourceBundleAccessorClass(final String fileName) {
        newDocument(directory, fileName, () -> {
            final FileTemplate template = FileTemplateManager.getInstance(project)
                    .getInternalTemplate("ResourceBundleAccessorClass.java");
            final Map<String, String> attributes = new HashMap<String, String>() {
                {
                    final String packageName = file.getPackageName();
                    put("PACKAGE_NAME", packageName);
                    put("BUNDLE_NAME", StringUtils.isEmpty(packageName) ? "messages" : packageName + ".messages");
                }
            };

            try {
                return template.getText(attributes);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void writeProperty(final String fileName, final String key, final String value) {
        appendDocument(directory, fileName,
                () -> StringUtil.escapeProperty(key, true) + "=" + StringUtil.escapeProperty(value, false));
    }

    private void writeNonNls(final PsiJavaToken javaToken) {
        final int newOrdinal = strings.indexOf(javaToken) + 1;
        doWithNearestComment(newOrdinal, (nearestComment, nearestOrdinal) -> {
            if (nearestComment == null) {
                writeNonNlsComment(newOrdinal);
            } else if (newOrdinal < nearestOrdinal) {
                nearestComment.writeNonNlsBefore(nearestOrdinal).newOrdinal(newOrdinal);
            } else if (newOrdinal > nearestOrdinal) {
                nearestComment.writeNonNlsAfter(nearestOrdinal).newOrdinal(newOrdinal);
            } else {
                throw new AssertionError("Following ordinal of non-nls comment already exists: " + newOrdinal);
            }
        });
    }

    private void newDocument(final PsiDirectory directory, final String fileName, final Supplier<String> text) {
        if (directory.findFile(fileName) != null) {
            return;
        }

        final PsiFile file = directory.createFile(fileName);
        final Document document = documentManager.getDocument(file);
        document.setText(text.get());
    }

    private void appendDocument(final PsiDirectory directory, final String fileName, final Supplier<String> text) {
        final PsiFile file = Optional.ofNullable(directory.findFile(fileName))
                .orElseGet(() -> directory.createFile(fileName));
        final Document document = documentManager.getDocument(file);

        final StringBuilder content = new StringBuilder(document.getText());

        if (content.length() > 0) {
            content.append("\n");
        }

        content.append(text.get());
        document.setText(content.toString());
    }

    private void doWithNearestComment(final int newOrdinal, final BiConsumer<CommentFacade, Integer> consumer) {
        int minimumGap = Integer.MAX_VALUE;
        int nearestOrdinal = 0;
        CommentFacade targetComment = null;

        for (final CommentFacade comment : comments) {
            for (final int ordinal : comment.getNonNlsOrdinals()) {
                final int gap = Math.abs(newOrdinal - ordinal);
                if (gap < minimumGap) {
                    targetComment = comment;
                    nearestOrdinal = ordinal;
                    minimumGap = gap;
                }
            }
        }

        consumer.accept(targetComment, nearestOrdinal);
    }

    private void writeNonNlsComment(final int ordinal) {
        document.insertString(lineEndOffset, SPACE + toNonNlsString(ordinal));
    }

    // Workaround: HighlightUtil.checkLiteralExpressionParsingError raises error when replacing PsiElement
    private void replaceElementByText(final PsiElement element, final String text) {
        final TextRange textRange = element.getTextRange();
        document.replaceString(textRange.getStartOffset(), textRange.getEndOffset(), text);
    }

    private static String toNonNlsString(final int ordinal) {
        return String.format(NON_NLS_FORMAT, ordinal);
    }

    private class CommentFacade {

        private final Map<Integer, Pair<Integer, Integer>> ordinals = new HashMap<>();

        private final PsiComment comment;
        private String text;

        CommentFacade(final PsiComment comment) {
            this.comment = comment;
            text = comment.getText();

            for (final Matcher matcher = NON_NLS_PATTERN.matcher(text); matcher.find(); ) {
                final int number = Integer.parseInt(matcher.group(1));
                final int start = matcher.start();
                final int end = matcher.end();
                ordinals.put(number, Pair.create(start, end));
            }
        }

        Set<Integer> getNonNlsOrdinals() {
            return ordinals.keySet();
        }

        NonNlsWriter writeNonNlsBefore(final int ordinal) {
            return new NonNlsWriter(ordinals.get(ordinal).getFirst(), o -> toNonNlsString(o) + SPACE);
        }

        NonNlsWriter writeNonNlsAfter(final int ordinal) {
            return new NonNlsWriter(ordinals.get(ordinal).getSecond(), o -> SPACE + toNonNlsString(o));
        }

        private class NonNlsWriter {

            private final int offset;
            private final IntFunction<String> nonNlsFunction;

            NonNlsWriter(final int offset, final IntFunction<String> nonNlsFunction) {
                this.offset = offset;
                this.nonNlsFunction = nonNlsFunction;
            }

            void newOrdinal(final int newOrdinal) {
                final StringBuilder sb = new StringBuilder(text);
                sb.insert(offset, nonNlsFunction.apply(newOrdinal));
                replaceElementByText(comment, sb.toString());
            }

        }

    }

}
