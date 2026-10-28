# XiaoAiBrain 混淆规则（V1 不开启混淆，此文件预留）
# Xposed 模块注意：若开启 minify，必须保留被 hook 的类名入口
-keep class com.xiaoaibrain.HookEntry { *; }
-keep class com.xiaoaibrain.MainActivity { *; }
-keep class com.xiaoaibrain.LogReceiver { *; }
