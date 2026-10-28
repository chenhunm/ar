package com.huanban.ar;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.*;
import java.util.*;

/** Read-only fixture provider, installed exclusively with the test APK. */
public class FixtureDocumentsProvider extends DocumentsProvider {
    private static final String[] FILES={"fixture.glb","fixture.gltf","fixture.vrm","mesh.bin"};
    private static final String[] COLS={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE};
    @Override public boolean onCreate(){return true;}
    private boolean valid(String id){return Arrays.asList(FILES).contains(id);}
    @Override public Cursor queryRoots(String[] projection){
        String[] cols=projection!=null?projection:new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES};
        MatrixCursor c=new MatrixCursor(cols);Object[] values=new Object[cols.length];
        for(int i=0;i<cols.length;i++){String k=cols[i];if(k.equals(Root.COLUMN_ROOT_ID))values[i]="fixtures";else if(k.equals(Root.COLUMN_DOCUMENT_ID))values[i]="root";else if(k.equals(Root.COLUMN_TITLE))values[i]="幻伴测试模型";else if(k.equals(Root.COLUMN_FLAGS))values[i]=Root.FLAG_SUPPORTS_IS_CHILD;else if(k.equals(Root.COLUMN_MIME_TYPES))values[i]="*/*";}
        c.addRow(values);return c;
    }
    private void add(MatrixCursor c,String id){
        if(!id.equals("root")&&!valid(id))throw new IllegalArgumentException("Unknown fixture");
        boolean root=id.equals("root");long size=0;
        if(!root)try(InputStream in=getContext().getAssets().open("fixtures/"+id)){size=in.available();}catch(IOException e){throw new IllegalStateException(e);}
        String[] cols=c.getColumnNames();Object[] values=new Object[cols.length];
        for(int i=0;i<cols.length;i++){String k=cols[i];if(k.equals(Document.COLUMN_DOCUMENT_ID))values[i]=id;else if(k.equals(Document.COLUMN_DISPLAY_NAME))values[i]=root?"幻伴测试模型":id;else if(k.equals(Document.COLUMN_MIME_TYPE))values[i]=root?Document.MIME_TYPE_DIR:"application/octet-stream";else if(k.equals(Document.COLUMN_FLAGS))values[i]=0;else if(k.equals(Document.COLUMN_SIZE))values[i]=size;}
        c.addRow(values);
    }
    @Override public Cursor queryDocument(String id,String[] projection){MatrixCursor c=new MatrixCursor(projection!=null?projection:COLS);add(c,id);return c;}
    @Override public Cursor queryChildDocuments(String id,String[] projection,String sort){MatrixCursor c=new MatrixCursor(projection!=null?projection:COLS);if(id.equals("root"))for(String name:FILES)add(c,name);return c;}
    @Override public boolean isChildDocument(String parent,String id){return parent.equals("root")&&valid(id);}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(!mode.equals("r")||!valid(id))throw new FileNotFoundException("Unsupported fixture");
        File f=new File(getContext().getCacheDir(),id);
        try(InputStream in=getContext().getAssets().open("fixtures/"+id);OutputStream out=new FileOutputStream(f)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}catch(IOException e){throw new FileNotFoundException(e.toString());}
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
