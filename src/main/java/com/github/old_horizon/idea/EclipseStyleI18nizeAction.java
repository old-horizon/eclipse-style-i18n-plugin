package com.github.old_horizon.idea;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.util.IncorrectOperationException;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;
import javax.swing.SwingConstants;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class EclipseStyleI18nizeAction extends PsiElementBaseIntentionAction {

    private static final Runnable NO_OP = () -> {
    };

    @Override
    public boolean isAvailable(@NotNull final Project project, final Editor editor, @NotNull final PsiElement element) {
        if (!(element.getLanguage().isKindOf(JavaLanguage.INSTANCE) && element instanceof PsiJavaToken)) {
            return false;
        }
        final PsiJavaToken javaToken = (PsiJavaToken) element;
        return javaToken.getTokenType() == JavaTokenType.STRING_LITERAL &&
                new LineModel(javaToken).needToExternalize(javaToken);
    }

    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    @Override
    public String getFamilyName() {
        return "Eclipse style i18n";
    }

    @NotNull
    @Override
    public String getText() {
        return getFamilyName();
    }

    @Override
    public void invoke(@NotNull final Project project, final Editor editor, @NotNull final PsiElement element)
            throws IncorrectOperationException {
        final PsiJavaToken javaToken = (PsiJavaToken) element;
        ApplicationManager.getApplication().invokeAndWait(() ->
                Commands.showPopupChooser(getFamilyName(), project, editor, javaToken));
    }

    private enum Commands {

        Externalize {
            @Override
            Runnable createWriteAction(final LineModel line, final PsiJavaToken javaToken) {
                final String text = javaToken.getText();
                final String key = Messages.showInputDialog(String.format("Substitution key for %s", text), name(),
                        null, getInitialValue(javaToken), null);

                if (StringUtils.isEmpty(key)) {
                    return NO_OP;
                }

                return () -> line.externalize(key, javaToken);
            }
        },
        Ignore {
            @Override
            Runnable createWriteAction(final LineModel line, final PsiJavaToken javaToken) {
                return () -> line.ignore(javaToken);
            }
        };

        private static final List<Commands> COMMANDS = Arrays.asList(Commands.values());
        private static final PopupRenderer RENDERER = new PopupRenderer();

        static void showPopupChooser(final String title, final Project project, final Editor editor,
                                     final PsiJavaToken javaToken) {
            JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(COMMANDS)
                    .setTitle(title)
                    .setRenderer(RENDERER)
                    .setItemChosenCallback(value -> execute(project, javaToken, value))
                    .setMovable(false)
                    .setResizable(false)
                    .setRequestFocus(true)
                    .createPopup()
                    .showInBestPositionFor(editor);
        }

        private static void execute(final Project project, final PsiJavaToken javaToken, final Commands value) {
            final LineModel model = new LineModel(javaToken);
            final Runnable runnable = value.createWriteAction(model, javaToken);
            WriteCommandAction.runWriteCommandAction(project,
                    () -> CommandProcessor.getInstance().executeCommand(project, runnable, value.name(), null));
        }

        private static String getInitialValue(final PsiJavaToken javaToken) {
            return Optional.ofNullable(PsiTreeUtil.getParentOfType(javaToken, PsiClass.class))
                    .map(c -> c.getName() + ".")
                    .orElse("");
        }

        abstract Runnable createWriteAction(LineModel line, PsiJavaToken javaToken);

        private static class PopupRenderer extends ColoredListCellRenderer<Commands> {
            @Override
            protected void customizeCellRenderer(@NotNull final JList<? extends Commands> list,
                                                 final Commands value, final int index, final boolean selected,
                                                 final boolean hasFocus) {
                setTextAlign(SwingConstants.CENTER);
                append(value.name());
            }
        }

    }

}
