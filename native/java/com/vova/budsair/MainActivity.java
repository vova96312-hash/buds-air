package com.vova.budsair;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BudsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
