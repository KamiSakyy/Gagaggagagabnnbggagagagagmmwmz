package com.genshin.anime.openworld;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.view.Gravity;
import android.graphics.Color;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0A0E1A"));
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Genshin Anime Open World");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0,0,0,24);

        TextView subtitle = new TextView(this);
        subtitle.setText("3D • Third Person • Open World • Android\nАниме-девушки как в Genshin Impact\n\n[DEMO APK — собран через GitHub Actions]\nSource: 949886/Genshin (Unity 6) • 1.2GB\nAPK ≤500MB • sensorLandscape • Vulkan");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.parseColor("#A0C4FF"));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(6, 1);

        TextView hint = new TextView(this);
        hint.setText("\nДальше: замени модель в Assets/Avatar на свою тян (VRoid VRM + HoyoToon),\nдобавь чанки в Addressables, собери новый APK через Actions → handoff/");
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor("#8899AA"));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 32, 0, 0);

        TextView version = new TextView(this);
        version.setText("v1.0 • com.genshin.anime.openworld • Built via Android SDK + Gradle 8.7");
        version.setTextSize(10);
        version.setTextColor(Color.parseColor("#556677"));
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, 48, 0, 0);

        root.addView(title);
        root.addView(subtitle);
        root.addView(hint);
        root.addView(version);

        setContentView(root);
    }
}
