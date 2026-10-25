package cz.mkdaniel.enterprisemessenger;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;

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

        // Initialize / load Elliptic Curve (EC) KeyPair on first app startup
        CryptoManager.getInstance(this).initializeKeysAsync();

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

        AccountStore accountStore = new AccountStore(this);
        if (!accountStore.isLoggedIn()) {
            navController.navigate(R.id.nav_login);
        } else {
            ServerConnectionManager conn = ServerConnectionManager.getInstance(this);
            conn.connectToServer(conn.getCurrentServerIp());
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
