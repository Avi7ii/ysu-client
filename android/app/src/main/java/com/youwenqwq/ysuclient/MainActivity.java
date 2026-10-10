package com.youwenqwq.ysuclient;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;

import com.getcapacitor.BridgeActivity;
import com.youwenqwq.ysuclient.widget.WidgetBridgePlugin;
import com.youwenqwq.ysuclient.notify.YsuNotifyPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WidgetBridgePlugin.class);
        registerPlugin(WebViewCompatPlugin.class);
        registerPlugin(YsuFilePlugin.class);
        registerPlugin(YsuNotifyPlugin.class);
        registerPlugin(LiquidBottomNavPlugin.class);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
    }
}
