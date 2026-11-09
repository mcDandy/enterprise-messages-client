package cz.mkdaniel.enterprisemessenger;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

import androidx.annotation.NonNull;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.appcompat.app.AppCompatActivity;

import cz.mkdaniel.enterprisemessenger.crypto.CryptoManager;
import cz.mkdaniel.enterprisemessenger.databinding.ActivityMainBinding;
import cz.mkdaniel.enterprisemessenger.net.AccountStore;
import cz.mkdaniel.enterprisemessenger.net.ServerConnectionManager;
import cz.mkdaniel.enterprisemessenger.notification.NotificationHelper;

public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration mAppBarConfiguration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize / load Elliptic Curve (EC) KeyPair on first app startup. A keystore that cannot
        // hand out the identity key means this device cannot prove who it is, so the user hears about
        // it - there is no silent software fallback that would quietly change his identity.
        CryptoManager.getInstance(this).initializeKeysAsync(failure ->
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.error_identity_key_unavailable,
                        Toast.LENGTH_LONG).show()));

        // Initialize notification channel and check/request permission for Android 13+
        NotificationHelper.createNotificationChannel(this);
        NotificationHelper.checkAndRequestPermission(this);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.appBarMain.toolbar);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_content_main);
        assert navHostFragment != null;
        NavController navController = navHostFragment.getNavController();

        NavigationView navigationView = binding.navView;
        if (navigationView != null) {
            mAppBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_transform, R.id.nav_settings, R.id.nav_login)
                    .setOpenableLayout(binding.drawerLayout)
                    .build();
            NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
            NavigationUI.setupWithNavController(navigationView, navController);

            // Update header with logged in user info
            View headerView = navigationView.getHeaderView(0);
            if (headerView != null) {
                TextView subtitleText = headerView.findViewById(R.id.textView);
                if (subtitleText != null) {
                    AccountStore accountStore = new AccountStore(this);
                    if (accountStore.isLoggedIn()) {
                        subtitleText.setText(accountStore.getUsername());
                    } else {
                        subtitleText.setText(R.string.nav_header_subtitle);
                    }
                }
            }
        }

        BottomNavigationView bottomNavigationView = binding.appBarMain.contentMain.bottomNavView;
        if (bottomNavigationView != null) {
            mAppBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_transform)
                    .build();
            NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
            NavigationUI.setupWithNavController(bottomNavigationView, navController);
        }

        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            int id = destination.getId();
            if (id == R.id.nav_login || id == R.id.nav_register) {
                if (bottomNavigationView != null) {
                    bottomNavigationView.setVisibility(View.GONE);
                }
                if (getSupportActionBar() != null) {
                    getSupportActionBar().hide();
                }
            } else {
                if (bottomNavigationView != null) {
                    bottomNavigationView.setVisibility(View.VISIBLE);
                }
                if (getSupportActionBar() != null) {
                    getSupportActionBar().show();
                }
            }
        });

        AccountStore accountStore = new AccountStore(this);
        ServerConnectionManager conn = ServerConnectionManager.getInstance(this);
        conn.addListener(new ServerConnectionManager.ConnectionListener() {
            @Override
            public void onConnectionStateChanged(boolean connected, String serverBaseUrl) {}

            @Override
            public void onMessageReceived(cz.mkdaniel.enterprisemessenger.ui.message.Message message) {}

            @Override
            public void onError(String errorMessage) {}

            @Override
            public void onAuthRequired() {
                runOnUiThread(() -> {
                    if (navigationView != null) {
                        View headerView = navigationView.getHeaderView(0);
                        if (headerView != null) {
                            TextView subtitleText = headerView.findViewById(R.id.textView);
                            if (subtitleText != null) {
                                subtitleText.setText(R.string.nav_header_subtitle);
                            }
                        }
                    }
                    NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment_content_main);
                    if (navHost != null) {
                        NavController controller = navHost.getNavController();
                        int currentId = controller.getCurrentDestination() != null ? controller.getCurrentDestination().getId() : 0;
                        if (currentId != R.id.nav_login && currentId != R.id.nav_register) {
                            androidx.navigation.NavOptions navOptions = new androidx.navigation.NavOptions.Builder()
                                    .setPopUpTo(R.id.nav_transform, true)
                                    .build();
                            controller.navigate(R.id.nav_login, null, navOptions);
                        }
                    }
                });
            }
        });

        if (!accountStore.isLoggedIn()) {
            navController.navigate(R.id.nav_login);
        } else {
            // No address is passed: the app only ever dials the physical server configured through
            // @string/server_ip, and what the user picks in the UI are virtual servers behind it.
            conn.connect();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean result = super.onCreateOptionsMenu(menu);
        NavigationView navView = findViewById(R.id.nav_view);
        if (navView == null) {
            getMenuInflater().inflate(R.menu.overflow, menu);
        }
        return result;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.nav_settings) {
            NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
            navController.navigate(R.id.nav_settings);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }
}
