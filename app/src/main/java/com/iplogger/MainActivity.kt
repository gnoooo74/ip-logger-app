package com.iplogger

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.work.*
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupTabs()
        requestPermissions()
    }

    private fun setupTabs() {
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> LogFragment()
                1 -> VerifyFragment()
                else -> FilterFragment()
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "📋 로그"
                1 -> "🔍 검증"
                else -> "🔎 필터"
            }
        }.attach()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        else
            requestBackgroundLocation()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1001 -> requestBackgroundLocation()
            1002 -> requestAllFilesAccessIfNeeded()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1003) {
            // 사용자가 설정 화면에서 허용/거부하고 돌아온 뒤 로깅 시작
            // (거부해도 위치 기반 로그는 계속 동작하도록 앱을 막지는 않음)
            startLogging()
        }
    }

    /**
     * Android 11(API 30)+ 에서는 Downloads/ip_logs 같은 공용 폴더에
     * 앱이 만든 파일이라도 "모든 파일 관리" 특수 권한 없이는 접근이 막힐 수 있다.
     * 일반 런타임 권한과 달리 다이얼로그가 아니라 설정 화면으로 유도해야 한다.
     */
    private fun requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 1003)
            } catch (e: Exception) {
                try {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 1003
                    )
                } catch (e2: Exception) {
                    startLogging()
                }
            }
        } else {
            startLogging()
        }
    }

    private fun startLogging() {
        scheduleIpLogging(this)
        registerNetworkCallback()
        registerReceiver(
            AirplaneModeReceiver(),
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        )
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    IpLogWorker.logIpNow(applicationContext)
                }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    1002
                )
                return
            }
        }
        requestAllFilesAccessIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

fun scheduleIpLogging(context: android.content.Context) {
    val request = OneTimeWorkRequestBuilder<IpLogWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()
    WorkManager.getInstance(context).enqueue(request)
}
