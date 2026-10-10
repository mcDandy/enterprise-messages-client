package cz.mkdaniel.enterprisemessenger.ui.transform;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

import cz.mkdaniel.enterprisemessenger.ui.server.ServerRepository;

public class TransformViewModel extends AndroidViewModel {

    private final MutableLiveData<List<ServerViewItem>> mItems;

    public TransformViewModel(@NonNull Application application) {
        super(application);
        mItems = new MutableLiveData<>();
        loadServers();
    }

    public LiveData<List<ServerViewItem>> getItems() {
        return mItems;
    }

    public void loadServers() {
        List<ServerViewItem> servers = ServerRepository.getInstance().getServers(getApplication());
        mItems.setValue(servers);
    }

    public ServerViewItem addServer(String name, String ipAddress) {
        ServerViewItem newServer = ServerRepository.getInstance().addServer(getApplication(), name, ipAddress);
        loadServers();
        return newServer;
    }
}
