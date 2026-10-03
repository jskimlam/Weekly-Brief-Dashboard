package com.lam.weeklyfeedstocks;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://jskimlam.github.io/Weekly-Brief-Dashboard/";
    private static final String HOME_HOST = "jskimlam.github.io";
    private static final int REQ_WRITE_STORAGE = 2101;
    private WebView webView;
    private ImageView splashView;
    private boolean firstPageShown = false;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private String pendingDataUrl, pendingFileName, pendingMimeType;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7,17,31));
        getWindow().setNavigationBarColor(Color.rgb(7,17,31));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setFitsSystemWindows(true);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splashView = new ImageView(this);
        splashView.setImageResource(R.drawable.lam_weekly_splash);
        splashView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        splashView.setBackgroundColor(Color.WHITE);
        root.addView(splashView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setDefaultTextEncodingName("UTF-8");
        s.setUserAgentString(s.getUserAgentString()+" LAMWeeklyAndroid/1.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true);

        webView.addJavascriptInterface(new AndroidBridge(),"AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new LAMWebViewClient());
        webView.setDownloadListener(new LAMDownloadListener());

        if (state != null) {
            webView.restoreState(state);
            hideSplash();
        } else {
            if (!hasNetwork()) Toast.makeText(this,"인터넷 연결을 확인해 주세요.",Toast.LENGTH_LONG).show();
            webView.loadUrl(HOME_URL);
        }
    }

    private void hideSplash() {
        if (splashView == null || splashView.getVisibility()!=View.VISIBLE) return;
        splashView.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            splashView.setVisibility(View.GONE);
            splashView.setAlpha(1f);
        }).start();
    }

    @Override protected void onSaveInstanceState(Bundle out){ webView.saveState(out); super.onSaveInstanceState(out); }
    @Override public void onBackPressed(){ if(webView!=null && webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy(){
        if(webView!=null){ webView.removeJavascriptInterface("AndroidBridge"); webView.stopLoading(); webView.destroy(); }
        ioExecutor.shutdownNow(); super.onDestroy();
    }

    private boolean hasNetwork(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null) return true;
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M){
            Network n=cm.getActiveNetwork(); if(n==null) return false;
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            return c!=null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        }
        return cm.getActiveNetworkInfo()!=null && cm.getActiveNetworkInfo().isConnected();
    }

    private class LAMWebViewClient extends WebViewClient {
        @Override public void onPageFinished(WebView view,String url){
            super.onPageFinished(view,url);
            if(!firstPageShown){ firstPageShown=true; view.postDelayed(MainActivity.this::hideSplash,180); }
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){ return handle(r.getUrl()); }
        @SuppressWarnings("deprecation") @Override public boolean shouldOverrideUrlLoading(WebView v,String url){ return handle(Uri.parse(url)); }
        private boolean handle(Uri uri){
            String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            if("http".equals(scheme)||"https".equals(scheme)){
                String host=uri.getHost();
                if(host!=null && HOME_HOST.equalsIgnoreCase(host)) return false;
                try{ startActivity(new Intent(Intent.ACTION_VIEW,uri)); }catch(Exception e){ Toast.makeText(MainActivity.this,"링크를 열 수 없습니다.",Toast.LENGTH_SHORT).show(); }
                return true;
            }
            if("mailto".equals(scheme)||"tel".equals(scheme)||"sms".equals(scheme)){
                try{ startActivity(new Intent(Intent.ACTION_VIEW,uri)); }catch(Exception e){ Toast.makeText(MainActivity.this,"연결할 앱이 없습니다.",Toast.LENGTH_SHORT).show(); }
                return true;
            }
            return false;
        }
    }

    private class LAMDownloadListener implements DownloadListener {
        @Override public void onDownloadStart(String url,String ua,String disposition,String mime,long len){
            if(url==null || (!url.startsWith("http://")&&!url.startsWith("https://"))){ Toast.makeText(MainActivity.this,"앱 내부 저장 기능을 이용해 주세요.",Toast.LENGTH_SHORT).show(); return; }
            try{
                String name=URLUtil.guessFileName(url,disposition,mime);
                DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
                req.setTitle(name); req.setDescription("LAM Weekly 다운로드");
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String cookie=CookieManager.getInstance().getCookie(url); if(cookie!=null) req.addRequestHeader("Cookie",cookie);
                if(ua!=null) req.addRequestHeader("User-Agent",ua);
                if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_WRITE_STORAGE); return;
                }
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
                DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
                if(dm!=null){ dm.enqueue(req); Toast.makeText(MainActivity.this,"다운로드를 시작했습니다.",Toast.LENGTH_SHORT).show(); }
            }catch(Exception e){ Toast.makeText(MainActivity.this,"다운로드를 시작하지 못했습니다.",Toast.LENGTH_SHORT).show(); }
        }
    }

    private class AndroidBridge {
        @JavascriptInterface public void copyText(String text){
            runOnUiThread(() -> {
                ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(cm!=null) cm.setPrimaryClip(ClipData.newPlainText("LAM Weekly",text==null?"":text));
            });
        }
        @JavascriptInterface public void saveBase64File(String dataUrl,String fileName,String mimeType){
            if(dataUrl==null||dataUrl.isEmpty()) return;
            final String mime=(mimeType==null||mimeType.trim().isEmpty())?"application/octet-stream":mimeType;
            final String name=sanitizeFileName(fileName,mime);
            if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
                pendingDataUrl=dataUrl; pendingFileName=name; pendingMimeType=mime;
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_WRITE_STORAGE));
                return;
            }
            ioExecutor.execute(() -> saveBase64FileInternal(dataUrl,name,mime));
        }
    }

    private String sanitizeFileName(String name,String mime){
        String ext="application/pdf".equalsIgnoreCase(mime)?".pdf":".png";
        String n=(name==null||name.trim().isEmpty())?"LAM_Weekly"+ext:name.trim();
        n=n.replaceAll("[\\\\/:*?\"<>|]+","_");
        if(!n.toLowerCase(Locale.ROOT).endsWith(ext)) n+=ext;
        return n;
    }

    private void saveBase64FileInternal(String dataUrl,String fileName,String mime){
        try{
            int comma=dataUrl.indexOf(','); String payload=comma>=0?dataUrl.substring(comma+1):dataUrl;
            byte[] bytes=Base64.decode(payload,Base64.DEFAULT);
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){
                ContentValues v=new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME,fileName);
                v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/LAM Weekly");
                v.put(MediaStore.MediaColumns.IS_PENDING,1);
                Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                if(uri==null) throw new IllegalStateException("MediaStore insert failed");
                try(OutputStream os=getContentResolver().openOutputStream(uri)){ if(os==null) throw new IllegalStateException("No stream"); os.write(bytes); os.flush(); }
                v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING,0); getContentResolver().update(uri,v,null,null);
            } else {
                File dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if(!dir.exists()&&!dir.mkdirs()) throw new IllegalStateException("No download folder");
                try(FileOutputStream fos=new FileOutputStream(new File(dir,fileName))){ fos.write(bytes); fos.flush(); }
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,"다운로드/LAM Weekly에 저장되었습니다.",Toast.LENGTH_LONG).show());
        }catch(Exception e){
            runOnUiThread(() -> Toast.makeText(MainActivity.this,"파일 저장에 실패했습니다.",Toast.LENGTH_LONG).show());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_WRITE_STORAGE && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED && pendingDataUrl!=null){
            String d=pendingDataUrl,n=pendingFileName,m=pendingMimeType;
            pendingDataUrl=null; pendingFileName=null; pendingMimeType=null;
            ioExecutor.execute(() -> saveBase64FileInternal(d,n,m));
        } else if(requestCode==REQ_WRITE_STORAGE){
            pendingDataUrl=null; pendingFileName=null; pendingMimeType=null;
            Toast.makeText(this,"저장 권한이 허용되지 않았습니다.",Toast.LENGTH_SHORT).show();
        }
    }
}
