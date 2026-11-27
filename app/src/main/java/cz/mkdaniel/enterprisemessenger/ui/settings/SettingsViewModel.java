package cz.mkdaniel.enterprisemessenger.ui.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * Holds settings-screen state and operations.
 *
 */
public class SettingsViewModel extends ViewModel {

    private final MutableLiveData<String> mText;

    public SettingsViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("This is settings fragment");
    }

    /**
     * Returns the text.
     *
     * @return the text value
     */
    public LiveData<String> getText() {
        return mText;
    }
}