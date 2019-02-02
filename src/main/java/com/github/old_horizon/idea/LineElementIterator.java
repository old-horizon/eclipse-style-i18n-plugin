package com.github.old_horizon.idea;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

import java.util.Iterator;

class LineElementIterator implements Iterator<PsiElement> {

    private final PsiFile file;
    private final int endOffset;

    private int offset;

    LineElementIterator(final PsiFile file, final int startOffset, final int endOffset) {
        this.file = file;
        this.endOffset = endOffset;
        offset = startOffset;
    }

    @Override
    public boolean hasNext() {
        return offset < endOffset;
    }

    @Override
    public PsiElement next() {
        final PsiElement element = file.findElementAt(offset);
        offset = element.getTextRange().getEndOffset();
        return element;
    }

}
