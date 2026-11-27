package cz.mkdaniel.enterprisemessenger.net;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.InetAddresses;
import android.os.Build;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Gates the Local Network Permission introduced with the Local Network Protections.
 * <p>
 * {@code INTERNET} alone no longer buys an app its way onto the LAN: on Android 17 (API 37) an app
 * targeting API 37 has every outbound TCP connection to a local address dropped unless
 * {@link Manifest.permission#ACCESS_LOCAL_NETWORK} has been granted. The drop happens deep in the
 * networking stack, so OkHttp - and every other stack built on sockets - only ever sees a
 * <b>connect timeout</b>, never a permission error. That is exactly what makes this easy to miss:
 * the server looks unreachable, while the same address works from a browser or from
 * {@code adb shell} on the very same phone (shell is not an app uid and is exempt).
 * <p>
 * The deployment of this app is normally a machine on the same network as the phone, so the
 * permission is not optional in development. It is deliberately not requested when
 * {@code @string/server_ip} names a public host, because then it really is only {@code INTERNET}
 * that is used, and asking for a runtime permission the app does not need is a privacy cost the
 * user should not pay.
 */
public final class LocalNetworkPermission {

    private static final String TAG = "LocalNetworkPerm";

    /**
     * Request code of the permission prompt, distinct from the notification one.
     */
    public static final int PERMISSION_REQUEST_CODE = 1002;

    private LocalNetworkPermission() {
    }

    /**
     * Whether local traffic is already allowed for this installation.
     * <p>
     * Below API 37 the permission does not exist and {@code INTERNET} carries an implicit grant, so
     * asking for it would only produce a prompt-less denial on a device where the LAN already works.
     *
     * @param context the Android context used to access application resources and services
     * @return granted.
     */
    public static boolean isGranted(Context context) {
        if (Build.VERSION.SDK_INT < 37) {
            return true;
        }

        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether the app may dial {@code host} right now: either local network access is held, or the
     * host is not on the local network and never needed the permission.
     *
     * @param context the Android context used to access application resources and services
     * @param host the host name or address
     * @return whether the may connect condition holds
     */
    public static boolean mayConnect(Context context, String host) {
        return isGranted(context) || !needsLocalNetworkAccess(host);
    }

    /**
     * Launches the prompt through the caller's own launcher, so a Fragment keeps its result callback.
     * Pair it with {@link #mayConnect(Context, String)}; asking when the answer is already "may
     * connect" would show a prompt the user has nothing to decide about.
     *
     * @param launcher the Android activity-result launcher to use
     */
    public static void requestUsing(@NonNull ActivityResultLauncher<String> launcher) {
        if (Build.VERSION.SDK_INT < 37) {
            // No such permission below API 37; launching would only produce a prompt-less denial.
            return;
        }

        launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
    }

    /**
     * Asks for local network access when the configured server is a local address and the user has
     * not granted it yet. Does nothing when the server is a public host or when access is granted,
     * so a normal sign-in on a production deployment never shows a prompt.
     *
     * @param host {@link ServerConnectionManager#WS_HOST}, i.e. the value of {@code @string/server_ip}
     *
     * @param activity the activity requesting the permission
     */
    public static void checkAndRequest(Activity activity, String host) {
        // The constant and the enforcement both exist from API 37; below it the permission is not
        // defined and asking would only yield a prompt-less denial.
        if (Build.VERSION.SDK_INT < 37 || isGranted(activity) || !needsLocalNetworkAccess(host)) {
            return;
        }

        Log.d(TAG, "Server " + host + " is a local address; requesting "
                + Manifest.permission.ACCESS_LOCAL_NETWORK);
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.ACCESS_LOCAL_NETWORK}, PERMISSION_REQUEST_CODE);
    }

    /**
     * Whether {@code host} lives on the local network and therefore needs the permission.
     * <p>
     * A literal address is classified by its range (private and link-local are local, anything else
     * is a public host). A hostname is treated as local: resolving it here would mean a DNS lookup
     * on the way to the first prompt, and a name on a company network is the common case, so the
     * conservative answer keeps the app working. A deployment that uses a public host name and wants
     * to avoid the prompt can set {@code @string/server_ip} to that host's public IP.
     *
     * @param host the host name or address
     * @return whether the needs local network access condition holds
     */
    public static boolean needsLocalNetworkAccess(String host) {
        final String trimmed = host == null ? "" : host.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        if (!InetAddresses.isNumericAddress(trimmed)) {
            return true;
        }

        // Loopback belongs to the separate localhost protections and is not local network traffic;
        // an emulator's 10.0.2.2 and every LAN address are site-local and therefore are.
        return isLocalAddress(trimmed);
    }

    /**
     * Returns whether local address.
     *
     * @param literalAddress whether the address should be treated as a literal IP address
     * @return local address.
     */
    private static boolean isLocalAddress(String literalAddress) {
        try {
            final java.net.InetAddress address = java.net.InetAddress.getByName(literalAddress);

            return address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (final Exception failure) {
            // Unclassifiable literal: assume local, because a missing permission silently times out
            // whereas an unnecessary prompt only costs the user one tap.
            Log.w(TAG, "Could not classify " + literalAddress + ", treating it as local", failure);

            return true;
        }
    }
}
