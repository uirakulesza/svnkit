/*
 * ====================================================================
 * Copyright (c) 2004 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://tmate.org/svn/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.io.fs;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.io.diff.SVNDiffInstruction;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindow;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindowBuilder;

/**
 * @version 1.0
 * @author  TMate Software Ltd.
 */
public class FSInputStream extends InputStream {
    private LinkedList myRepStateList = new LinkedList();

    private int myChunkIndex;
    
    private boolean isChecksumFinalized;
    
    private String myHexChecksum;
    private long myLength;
    private long myOffset;
    
    private SVNDiffWindowBuilder myDiffWindowBuilder = SVNDiffWindowBuilder.newInstance();
    private MessageDigest myDigest;
    private byte[] myBuffer;
    private int myBufPos = 0;
    
    private FSInputStream(FSRepresentation representation, FSFS owner) throws SVNException {
        myChunkIndex = 0;
        isChecksumFinalized = false;
        myHexChecksum = representation.getHexDigest();
        myOffset = 0;
        myLength = representation.getExpandedSize();
        try {
            myDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException nsae) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, "MD5 implementation not found: {0}", nsae.getLocalizedMessage());
            SVNErrorManager.error(err, nsae);
        }
        try{
            FSRepresentationState.buildRepresentationList(representation, myRepStateList, owner);
        }catch(SVNException svne){
            close();
            throw svne;
        }
    }
    
    public static InputStream createDeltaStream(FSRevisionNode fileNode, FSFS owner) throws SVNException {
        if(fileNode == null){
            return SVNFileUtil.DUMMY_IN;
        }else if (fileNode.getType() != SVNNodeKind.FILE) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FILE, "Attempted to get textual contents of a *non*-file node");
            SVNErrorManager.error(err);
        }
        FSRepresentation representation = fileNode.getTextRepresentation(); 
        if(representation == null){
            return SVNFileUtil.DUMMY_IN; 
        }
        return new BufferedInputStream(new FSInputStream(representation, owner));
    }

    public static InputStream createDeltaStream(FSRepresentation fileRep, FSFS owner) throws SVNException {
        if(fileRep == null){
            return SVNFileUtil.DUMMY_IN;
        }
        return new BufferedInputStream(new FSInputStream(fileRep, owner));
    }

    public int read(byte[] buf, int offset, int length) throws IOException {
        try{
            int r = readContents(buf, offset, length); 
            return r == 0 ? -1 : r;
        }catch(SVNException svne){
            throw new IOException(svne.getMessage());
        }
    }
    
    public int read() throws IOException {
        byte[] buf = new byte[1];
        int r = 0;
        try{
            r = readContents(buf, 0, 1);
        }catch(SVNException svne){
            throw new IOException(svne.getMessage());
        }
        return r == 0 ? -1 : (int)(buf[0] & 0xFF);
    }
    
    private int readContents(byte[] buf, int offset, int length) throws SVNException {
        length = getContents(buf, offset, length);

        if(!isChecksumFinalized){
            myDigest.update(buf, offset, length);
            myOffset += length;
        
            if(myOffset == myLength){
                isChecksumFinalized = true;
                String hexDigest = SVNFileUtil.toHexDigest(myDigest);

                if (!myHexChecksum.equals(hexDigest)) {
                    SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Checksum mismatch while reading representation:\n   expected:  {0}\n     actual:  {1}", new Object[]{myHexChecksum, hexDigest});
                    SVNErrorManager.error(err);
                }
            }
        }
        
        return length;
    }
    
    private int getContents(byte[] buffer, int offset, int length) throws SVNException {
        int remaining = length;
        int targetPos = offset;

        while(remaining > 0){
            if(myBuffer != null){
                int copyLength = myBuffer.length - myBufPos;
                if(copyLength > remaining){
                    copyLength = remaining;
                }

                System.arraycopy(myBuffer, myBufPos, buffer, targetPos, copyLength);
                myBufPos += copyLength;
                targetPos += copyLength;
                remaining -= copyLength;

                if(myBufPos == myBuffer.length){
                    myBuffer = null;
                    myBufPos = 0;
                }
            }else{
                FSRepresentationState resultState = (FSRepresentationState)myRepStateList.getFirst();
                
                if(resultState.offset == resultState.end){
                    break;
                }
                
                int startIndex = 0;
                
                for(ListIterator states = myRepStateList.listIterator(); states.hasNext();){
                    FSRepresentationState curState = (FSRepresentationState)states.next();

                    try{
                        while(curState.chunkIndex < myChunkIndex){
                            skipDiffWindow(curState.file);
                            curState.chunkIndex++;
                            curState.offset = curState.file.position();
                            if(curState.offset >= curState.end){
                                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Reading one svndiff window read beyond the end of the representation");
                                SVNErrorManager.error(err);
                            }
                        }
                    }catch(IOException ioe){
                        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
                        SVNErrorManager.error(err, ioe);
                    }

                    startIndex = myRepStateList.indexOf(curState);
                    myDiffWindowBuilder.reset(SVNDiffWindowBuilder.OFFSET);
                    try{
                        long currentPos = curState.file.position();
                        myDiffWindowBuilder.accept(curState.file);
                        curState.file.seek(currentPos);
                    }catch(IOException ioe){
                        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
                        SVNErrorManager.error(err, ioe);
                    }
                    SVNDiffWindow window = myDiffWindowBuilder.getDiffWindow();
                    boolean hasCopiesFromSource = false;
                    for(Iterator instructions = window.instructions(); !hasCopiesFromSource && instructions.hasNext();){
                        SVNDiffInstruction instruction = (SVNDiffInstruction) instructions.next(); 
                        if(instruction.type == SVNDiffInstruction.COPY_FROM_SOURCE) {
                            hasCopiesFromSource = true;
                        }
                    }
                    if(!hasCopiesFromSource){
                        break;
                    }
                }
                myBuffer = getNextTextChunk(startIndex);
            }
        }
        return targetPos;
    }
    
    private byte[] getNextTextChunk(int startIndex) throws SVNException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] targetView = null;
        byte[] sourceView = null;
        for(ListIterator states = myRepStateList.listIterator(startIndex + 1); states.hasPrevious();){
            FSRepresentationState state = (FSRepresentationState)states.previous();
            data.reset();
            SVNDiffWindow window = null;
            try{
                window = readWindow(state, myChunkIndex, data);
            }catch(IOException ioe){
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, ioe.getLocalizedMessage());
                SVNErrorManager.error(err, ioe);
            }
            targetView = window.apply(sourceView, data.toByteArray());
            if(states.hasPrevious()){
                sourceView = targetView;
            }
        }
        myChunkIndex++;
        return targetView;
    }

    private SVNDiffWindow readWindow(FSRepresentationState state, int thisChunk, OutputStream dataBuf) throws SVNException, IOException {
        if(state.chunkIndex > thisChunk){
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.UNKNOWN, "Fatal error while reading diff windows");
            SVNErrorManager.error(err);
        }
        
        while(state.chunkIndex < thisChunk){
            skipDiffWindow(state.file);
            state.chunkIndex++;
            state.offset = state.file.position();
            if(state.offset >= state.end){
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Reading one svndiff window read beyond the end of the representation");
                SVNErrorManager.error(err);
            }
        }

        myDiffWindowBuilder.reset(SVNDiffWindowBuilder.OFFSET);
        myDiffWindowBuilder.accept(state.file);
        SVNDiffWindow window = myDiffWindowBuilder.getDiffWindow();
        long length = window.getNewDataLength();
        byte[] buffer = new byte[(int)length];
        ByteBuffer bBuffer = ByteBuffer.wrap(buffer, 0, (int) length);
        int read = state.file.read(bBuffer);
        if(read < length){
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.SVNDIFF_UNEXPECTED_END, "Unexpected end of svndiff input");
            SVNErrorManager.error(err);
        }
        state.chunkIndex++;
        state.offset = state.file.position();
        if(state.offset > state.end){
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_CORRUPT, "Reading one svndiff window read beyond the end of the representation");
            SVNErrorManager.error(err);
        }
        dataBuf.write(buffer);
        return window;
    }
    
    private void skipDiffWindow(FSFile file) throws IOException, SVNException {
        myDiffWindowBuilder.reset(SVNDiffWindowBuilder.OFFSET);
        myDiffWindowBuilder.accept(file);
        SVNDiffWindow window = myDiffWindowBuilder.getDiffWindow();
        long len = window.getNewDataLength();
        long curPos = file.position();
        file.seek(curPos + len);
    }
    
    public void close() {
        for(Iterator states = myRepStateList.iterator(); states.hasNext();){
            FSRepresentationState state = (FSRepresentationState)states.next();
            if(state.file != null){
                state.file.close();
            }
            states.remove();
        }
    }
}
