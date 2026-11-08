package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

/**
 * Holds the list of <b>virtual</b> servers shown by {@link TransformFragment}.
 * <p>
 * Virtual servers are not stored on the device any more: they are the server's {@code MessageServer}
 * entities, fetched from the one physical server on every load. The repository therefore only keeps
 * the in-flight result, and every mutation goes over REST and reloads the list afterwards so the
 * list always reflects what the server accepted (including the admin flag, which the server owns).
 */
public class TransformViewModel extends AndroidViewModel {

    private final MutableLiveData<List<ServerViewItem>> mItems;
    private final MutableLiveData<String> mErrorMessage;

    public TransformViewModel(@NonNull Application application) {
        super(application);
        mItems = new MutableLiveData<>();
        mErrorMessage = new MutableLiveData<>();
        loadServers();
    }

    public LiveData<List<ServerViewItem>> getItems() {
        return mItems;
    }

    /** Message to surface as a toast whenever a request fails, or {@code null} when nothing failed. */
    public LiveData<String> getErrorMessage() {
        return mErrorMessage;
    }

    /**
     * Called once the screen has shown the failure, so re-entering the screen (or another observer
     * attaching) does not repeat a toast for an error the user already saw.
     */
    public void clearErrorMessage() {
        mErrorMessage.setValue(null);
    }

    /**
     * Loads the virtual servers the signed-in user belongs to, together with his role in each.
     * The callback already arrives on the main thread.
     */
    public void loadServers() {
        ServerConnectionManager.getInstance(getApplication()).fetchServers(
                new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(List<ServerViewItem> servers) {
                        List<ServerViewItem> current = mItems.getValue();

                        // The unread badges live in the UI only; carry them over the reload so a
                        // refresh does not silently mark unread servers as read.
                        if (current != null && servers != null) {
                            for (ServerViewItem previous : current) {
                                for (ServerViewItem fresh : servers) {
                                    if (fresh.getServerId() == previous.getServerId()) {
                                        fresh.setUnreadCount(previous.getUnreadCount());
                                    }
                                }
                            }
                        }

                        mItems.setValue(servers == null ? new ArrayList<>() : servers);
                    }

                    @Override
                    public void onFailure(String error) {
                        mErrorMessage.setValue(error);
                    }
                });
    }

    /**
     * Creates a virtual server; the creator becomes its administrator, so the reload shows the
     * rename and delete affordances for it straight away.
     */
    public void addServer(String name) {
        ServerConnectionManager.getInstance(getApplication()).createServer(name,
                new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(ServerViewItem server) {
                        loadServers();
                    }

                    @Override
                    public void onFailure(String error) {
                        mErrorMessage.setValue(error);
                    }
                });
    }

    /**
     * Renames a virtual server; the server accepts it from its administrator only, and a 403 is
     * reported as a permission error rather than a log-out.
     */
    public void renameServer(long serverId, String name) {
        ServerConnectionManager.getInstance(getApplication()).renameServer(serverId, name,
                new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(Void result) {
                        loadServers();
                    }

                    @Override
                    public void onFailure(String error) {
                        mErrorMessage.setValue(error);
                    }
                });
    }

    public void deleteServer(long serverId) {
        ServerConnectionManager.getInstance(getApplication()).deleteServer(serverId,
                new ServerConnectionManager.Callback<>() {
                    @Override
                    public void onResult(Void result) {
                        loadServers();
                    }

                    @Override
                    public void onFailure(String error) {
                        mErrorMessage.setValue(error);
                    }
                });
    }
}
