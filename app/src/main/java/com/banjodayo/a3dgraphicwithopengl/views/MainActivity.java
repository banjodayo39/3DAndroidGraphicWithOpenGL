package com.banjodayo.a3dgraphicwithopengl.views;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

import com.banjodayo.a3dgraphicwithopengl.R;
import com.banjodayo.a3dgraphicwithopengl.glViews.GraphicView;

public class MainActivity extends AppCompatActivity {

    private  GraphicView graphicView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView( R.layout.activity_main);
        graphicView = new GraphicView(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (graphicView != null) {
            graphicView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        graphicView.onPause();
        super.onDestroy();
    }
}