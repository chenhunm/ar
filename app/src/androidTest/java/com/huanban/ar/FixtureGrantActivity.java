package com.huanban.ar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Standalone test-APK process must not rely on the target APK's Kotlin runtime. */
public class FixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String authority="com.huanban.ar.test.fixtures";
        for(String name:new String[]{"fixture.glb","fixture.vrm","fixture.gltf","mesh.bin"})
            grantUriPermission("com.huanban.ar",DocumentsContract.buildDocumentUri(authority,name),Intent.FLAG_GRANT_READ_URI_PERMISSION);
        grantUriPermission("com.huanban.ar",DocumentsContract.buildTreeDocumentUri(authority,"root"),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        android.util.Log.i("HuanBanFixtures","Granted fixture documents and tree");
        finish();
    }
}
