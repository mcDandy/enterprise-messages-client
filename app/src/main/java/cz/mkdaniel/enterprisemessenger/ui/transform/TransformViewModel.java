package cz.mkdaniel.enterprisemessenger.ui.transform;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import cz.mkdaniel.enterprisemessenger.R;

public class TransformViewModel extends ViewModel {

    private final MutableLiveData<List<ServerViewItem>> mItems;

    public TransformViewModel() {
        mItems = new MutableLiveData<>();
        List<ServerViewItem> items = new ArrayList<>();

        int[] avatarDrawables = {
                R.drawable.avatar_1,
                R.drawable.avatar_2,
                R.drawable.avatar_3,
                R.drawable.avatar_4,
                R.drawable.avatar_5,
                R.drawable.avatar_6,
                R.drawable.avatar_7,
                R.drawable.avatar_8,
                R.drawable.avatar_9,
                R.drawable.avatar_10,
                R.drawable.avatar_11,
                R.drawable.avatar_12,
                R.drawable.avatar_13,
                R.drawable.avatar_14,
                R.drawable.avatar_15,
                R.drawable.avatar_16
        };

        for (int i = 0; i < avatarDrawables.length; i++) {
            String serverId = "srv_" + (i + 1);
            String serverName = "Server #" + (i + 1);
            String ipAddress = "10.0.0." + (i + 1);
            int unreadCount = (i == 0) ? 3 : ((i == 2) ? 1 : 0);
            items.add(new ServerViewItem(serverId, serverName, ipAddress, avatarDrawables[i], unreadCount));
        }

        mItems.setValue(items);
    }

    public LiveData<List<ServerViewItem>> getItems() {
        return mItems;
    }
}
