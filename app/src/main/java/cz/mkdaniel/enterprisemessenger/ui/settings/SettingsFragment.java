package cz.mkdaniel.enterprisemessenger.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import cz.mkdaniel.enterprisemessenger.R;
import cz.mkdaniel.enterprisemessenger.databinding.FragmentSettingsBinding;
import cz.mkdaniel.enterprisemessenger.net.AccountStore;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        SettingsViewModel settingsViewModel =
                new ViewModelProvider(this).get(SettingsViewModel.class);

        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        settingsViewModel.getText().observe(getViewLifecycleOwner(), binding.textSettings::setText);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        AccountStore accountStore = new AccountStore(requireContext());
        if (accountStore.isLoggedIn()) {
            binding.textLoggedUser.setText(getString(R.string.text_logged_in_as, accountStore.getUsername()));
        } else {
            binding.textLoggedUser.setText(R.string.text_not_logged_in);
        }

        binding.buttonLogout.setOnClickListener(v -> {
            new AccountStore(requireContext()).clearSession();
            ServerConnectionManager.getInstance(requireContext()).disconnect();
            NavController navController = NavHostFragment.findNavController(this);
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.nav_transform, true)
                    .build();
            navController.navigate(R.id.nav_login, null, navOptions);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
