package droiddebughunter;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.UserHandle;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only app_process helper. Never installs an APK or starts an application. */
public final class AppLabels {
    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Expected Android user ID");
            int user = Integer.parseInt(args[0]);
            if (user < 0) throw new IllegalArgumentException("Invalid Android user ID");
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            try { context = context.createPackageContext("com.android.shell", 0); }
            catch (PackageManager.NameNotFoundException ignored) { }
            UserHandle handle = UserHandle.class.getDeclaredConstructor(int.class).newInstance(user);
            context = (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class).invoke(context, handle, 0);
            PackageManager pm = context.getPackageManager();
            JSONArray apps = new JSONArray();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                try {
                    CharSequence label = pm.getApplicationLabel(info);
                    if (label != null && label.length() > 0 && !label.toString().equals(info.packageName))
                        apps.put(new JSONObject().put("packageName", info.packageName).put("name", label.toString()));
                } catch (RuntimeException ignored) { /* A removed or unreadable package must not abort the list. */ }
            }
            JSONObject result = new JSONObject().put("userId", user).put("applications", apps);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, "UTF-8"));
            out.println("DDH_APP_LABELS_V1:" + result.toString()); out.flush();
        } catch (Exception error) {
            System.err.println("App label lookup unavailable: " + error);
            System.exit(2);
        }
    }
}
