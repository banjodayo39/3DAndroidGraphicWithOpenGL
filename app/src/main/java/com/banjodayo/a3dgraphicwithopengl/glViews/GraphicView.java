package com.banjodayo.a3dgraphicwithopengl.glViews;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;

public class GraphicView extends  GLSurfaceView implements GLSurfaceView.Renderer {

    private static float mAngCtr = 0; //for animation
    long mLastTime = SystemClock.elapsedRealtime();

    //for touch event - dragging
    float mDragStartX = -1;
    float mDragStartY = -1;
    float mDownX = -1;
    float mDownY = -1;
    //we add the .0001 to avoid divide by 0 errors
    //starting camera angles
    static float mCamXang = 0.0001f;
    static float mCamYang = 180.0001f;
    //starting camera position
    static float mCamXpos = 0.0001f;
    static float mCamYpos = 60.0001f;
    static float mCamZpos = 180.0001f;
    //distance from camera to view target
    float mViewRad = 100;
    //target values will get set in constructor
    static float mTargetY = 0;
    static float mTargetX = 0;
    static float mTargetZ = 0;
    //scene angles will get set in constructor
    static float mSceneXAng = 0.0001f;
    static float mSceneYAng = 0.0001f;

    float mScrHeight = 0; //screen height
    float mScrWidth  = 0; //screen width
    float mScrRatio  = 0; //width/height
    float mClipStart = 1; //start of clip region

    final double mDeg2Rad = Math.PI / 180.0; //Degrees To Radians
    final double mRad2Deg = 180.0 / Math.PI; //Radians To Degrees

    boolean mResetMatrix = false; //set to true when camera moves

    int[] mFrameTime = new int[20]; //frames used for avg fps
    int mFramePos = 0; //current fps frame position
    long mStartTime = SystemClock.elapsedRealtime(); //for fps
    int mFPSDispCtr = 0; //fps display interval
    float mFPS = 0; //actual fps value

    TextView mTxtMsg = null; //for displaying FPS
    final GraphicView mTagStore = this; //for SetTextMessage
    Handler mThreadHandler = new Handler(); //used in SetTextMessage

    //constants for scene objects in GPU buffer
    final int mFLOOR = 1;
    final int mBALL  = 2;
    final int mPOOL  = 3;
    final int mWALL  = 4;
    final int mDROP  = 5;
    final int mSPLASH = 6;

    //need to store length of each vertex buffer
    int[] mBufferLen = new int[] {0,0,0,0,0,0,0}; //0/Floor/Ball/Pool/Wall/Drop/Splash
    EGLDisplay mDisplay = null;
    EGLSurface mBufferSurface = null;
    EGLSurface mCurSurface = null;
    boolean mSurfaceToggle = true;

    //ball parameters
    int mBallRad = 10; //radius
    int mBallVSliceCnt = 20; //slices vertically - latitude line count
    int mBallHSliceCnt = 20;  //slices horizontally - longitude line count - must be even

    //fountain parameters
    int mStreamCnt = 10; //should divide evenly into 360
    int mDropsPerStream = 30; //should divide evenly into 180
    int mRepeatLen = 180/mDropsPerStream; //distance between drops
    float mArcRad = 30; //stream arc radius
    //for storing drop positions //3 floats per vertex [x/y/z]
    float[][] dropCoords = new float[mStreamCnt*mDropsPerStream][3];

    //pool parameters
    int mPoolSliceCnt = mStreamCnt; //side count
    float mPoolRad = 57f; //radius

    //accelerometer value set by activity
    public float AccelZ = 0;
    public float AccelY = 0;
    int mOrientation = 0; //portrait\landscape

    //options menu defaults
    public boolean ShowBall = true;
    public boolean ShowFloor = true;
    public boolean ShowFountain = true;
    public boolean ShowPool = true;
    public boolean RotateScene = true;
    public boolean UseTiltAngle = false;
    public boolean MultiBillboard = true;
    public boolean ShowFPS = true;
    public boolean Paused = false;

    public GraphicView(Activity pActivity)
    {
        super(pActivity);

        //use FrameLayout so we can put a TextView on top of the openGL screen
        FrameLayout layout = new FrameLayout(pActivity);

        //create view for text message (fps)
        mTxtMsg = new TextView(layout.getContext());
        mTxtMsg.setBackgroundColor(0x00FFFFFF); //transparent
        mTxtMsg.setTextColor(0xFF777777); //gray

        layout.addView(this); //add openGL surface
        layout.addView(mTxtMsg); //add text view
        pActivity.setContentView(layout);
        setRenderer(this); //initialize surface view

        //create listener for accelerometer sensor
        ((SensorManager)pActivity.getSystemService(Context.SENSOR_SERVICE)).registerListener(
                new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        //accelerometer does not change orientation so need to switch sensors
                        if (mOrientation == Configuration.ORIENTATION_PORTRAIT)
                            AccelY = event.values[1]; //use Y sensor
                        else
                            AccelY = event.values[0]; //use X sensor
                        AccelZ = event.values[2]; //Z
                    }
                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {} //ignore this event
                },
                ((SensorManager)pActivity.getSystemService(Context.SENSOR_SERVICE))
                        .getSensorList(Sensor.TYPE_ACCELEROMETER).get(0),SensorManager.SENSOR_DELAY_NORMAL);
    }

    public GraphicView(Context context) {
        super(context);
    }

    public GraphicView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    //called once
    @Override
    public void onSurfaceCreated(GL10 gl1, EGLConfig pConfig)
    {
        GL11 gl = (GL11)gl1; //we need 1.1 functionality
        //set background frame color
        gl.glClearColor(0f, 0f, 0f, 1.0f); //black
        //generate vertex arrays for scene objects
        BuildFloor(gl);
        BuildBall(gl);
        BuildPool(gl);
        BuildWall(gl);
        BuildDrop(gl);
        BuildSplash(gl);
    }

    void BuildFloor(GL11 gl)
    {
        //7*7+6*6 = 85 quads = 170 triangles = 510 vertices = 1530 floats[x/y/z]
        int sqrSize = 20;
        float vtx[] = new float[1530];
        int vtxCtr = 0;
        //we use the offset to produce the checkered pattern
        for (int x=-130, offset=0; x<130; x+=sqrSize, offset=sqrSize-offset)
        {
            for (int y=-130+offset; y<130; y+=(sqrSize*2))
            {
                //each square is 2 triangles = 6 vertices = 18 floats [x/y/z]
                vtx[vtxCtr]	= x;
                vtx[vtxCtr+ 1] =-2; //floor is 2 points below 0
                vtx[vtxCtr+ 2] = y;
                vtx[vtxCtr+ 3] = x+sqrSize;
                vtx[vtxCtr+ 4] =-2;
                vtx[vtxCtr+ 5] = y;
                vtx[vtxCtr+ 6] = x;
                vtx[vtxCtr+ 7] =-2;
                vtx[vtxCtr+ 8] = y+sqrSize;
                vtx[vtxCtr+ 9] = x+sqrSize;
                vtx[vtxCtr+10] =-2;
                vtx[vtxCtr+11] = y;
                vtx[vtxCtr+12] = x;
                vtx[vtxCtr+13] =-2;
                vtx[vtxCtr+14] = y+sqrSize;
                vtx[vtxCtr+15] = x+sqrSize;
                vtx[vtxCtr+16] =-2;
                vtx[vtxCtr+17] = y+sqrSize;
                vtxCtr+=18;
            }
        }

        StoreVertexData(gl, vtx, mFLOOR); //store in GPU buffer
    }

    void BuildBall(GL11 gl)
    {
        //need to add 1 to include last vertex
        float x[][] = new float[mBallVSliceCnt+1][mBallHSliceCnt+1];
        float y[][] = new float[mBallVSliceCnt+1][mBallHSliceCnt+1];
        float z[][] = new float[mBallVSliceCnt+1][mBallHSliceCnt+1];

        //create grid of vertices as if sphere was laid flat
        //start at top, go down by slice (180 degrees top to bottom)
        for (int vCtr = 0; vCtr <= mBallVSliceCnt; vCtr++)
        {
            double vAng = 180.0 / mBallVSliceCnt * vCtr;
            float sliceRad = (float) (mBallRad * Math.sin(vAng * mDeg2Rad));
            float sliceY = (float) (mBallRad * Math.cos(vAng * mDeg2Rad));
            float vertexY = sliceY;
            float vertexX = 0;
            float vertexZ = 0;
            //go around entire sphere, 360 degrees
            for (int hCtr = 0; hCtr <= mBallHSliceCnt; hCtr++)
            {
                double hAng = 360.0 / mBallHSliceCnt * hCtr;
                vertexX = (float) (sliceRad * Math.sin(hAng * mDeg2Rad));
                vertexZ = (float) (sliceRad * Math.cos(hAng * mDeg2Rad));
                y[vCtr][hCtr]=vertexY+60;
                x[vCtr][hCtr]=vertexX;
                z[vCtr][hCtr]=vertexZ;
            }
        }
        int hCnt = x[0].length;
        int vCnt = x.length;;

        //calculate triangle vertices for each quad
        //colors are drawn separately, only create vertices for one color
        //16*8 = 128 quads = 256 triangles = 768 vertices = 2304 floats [x/y/z]
        float vtx[] = new float[mBallVSliceCnt*mBallHSliceCnt/2*2*3*3];
        int vtxCtr = 0;
        for (int vCtr = 1; vCtr < vCnt; vCtr++)
            //use %2 to create checker pattern, hCtr+=2 to skip quads
            for (int hCtr = 1+vCtr%2; hCtr < hCnt; hCtr += 2)
            {
                vtx[vtxCtr]	= x[vCtr-1][hCtr-1];
                vtx[vtxCtr+ 1] = y[vCtr-1][hCtr-1];
                vtx[vtxCtr+ 2] = z[vCtr-1][hCtr-1];
                vtx[vtxCtr+ 3] = x[vCtr][hCtr-1];
                vtx[vtxCtr+ 4] = y[vCtr][hCtr-1];
                vtx[vtxCtr+ 5] = z[vCtr][hCtr-1];
                vtx[vtxCtr+ 6] = x[vCtr-1][hCtr];
                vtx[vtxCtr+ 7] = y[vCtr-1][hCtr];
                vtx[vtxCtr+ 8] = z[vCtr-1][hCtr];
                vtx[vtxCtr+ 9] = x[vCtr][hCtr-1];
                vtx[vtxCtr+10] = y[vCtr][hCtr-1];
                vtx[vtxCtr+11] = z[vCtr][hCtr-1];
                vtx[vtxCtr+12] = x[vCtr-1][hCtr];
                vtx[vtxCtr+13] = y[vCtr-1][hCtr];
                vtx[vtxCtr+14] = z[vCtr-1][hCtr];
                vtx[vtxCtr+15] = x[vCtr][hCtr];
                vtx[vtxCtr+16] = y[vCtr][hCtr];
                vtx[vtxCtr+17] = z[vCtr][hCtr];
                vtxCtr+=18;
            }

        StoreVertexData(gl, vtx, mBALL); //store in GPU buffer
    }

    void BuildPool(GL11 gl)
    {
        //center+10+end vertices = 12 vertices = 36 floats[x/y/z]
        float vtx[] = new float[(mPoolSliceCnt+2)*3];
        int vtxCtr = 0;
        //center vertex
        vtx[vtxCtr]   = 0;
        vtx[vtxCtr+1] = 4f; //6 points above floor
        vtx[vtxCtr+2] = 0;
        for (float fAngY = 0;fAngY <= 360;fAngY += 360/mPoolSliceCnt)
        {
            //vertices that create triangle fan, first vertex is repeated (0=360)
            vtxCtr+=3;
            vtx[vtxCtr] = mPoolRad*(float)Math.sin(fAngY*mDeg2Rad); //X
            vtx[vtxCtr+1] = 4f; //Y
            vtx[vtxCtr+2] = mPoolRad*(float)Math.cos(fAngY*mDeg2Rad); //Z
        }

        StoreVertexData(gl, vtx, mPOOL); //store in GPU buffer
    }

    void BuildWall(GL11 gl)
    {
        int wallSliceCnt = mPoolSliceCnt; //divides nicely into 360
        float wallRad = mPoolRad+2; //2 points larger than water to prevent Z-fight
        //wall is a triangle strip
        //defines start line then each square has 2 vertices
        //startline+10 squares = 22 vertices = 66 floats[x/y/z]
        float vtx[] = new float[(wallSliceCnt+1)*2*3];
        int vtxCtr = 0;
        //start line (left side of first square)
        //bottom vertex
        vtx[vtxCtr]   = 0;
        vtx[vtxCtr+1] = -1; //bottom of wall is below 0
        vtx[vtxCtr+2] = wallRad;
        //top vertex
        vtxCtr+=3;
        vtx[vtxCtr]   = 0;
        vtx[vtxCtr+1] = 9; //wall is 10 units high
        vtx[vtxCtr+2] = wallRad;
        //rotate around fountain center
        for (float ftnAngY = 360/wallSliceCnt; ftnAngY <= 360; ftnAngY += 360/wallSliceCnt)
        {
            //right side of each square (left side is from previous square)
            //bottom vertex
            vtxCtr+=3;
            vtx[vtxCtr] = wallRad*(float)Math.sin(ftnAngY*mDeg2Rad); //X
            vtx[vtxCtr+1] = -1; //Y
            vtx[vtxCtr+2] = wallRad*(float)Math.cos(ftnAngY*mDeg2Rad); //Z
            //top vertex
            vtxCtr+=3;
            vtx[vtxCtr] = wallRad*(float)Math.sin(ftnAngY*mDeg2Rad); //X
            vtx[vtxCtr+1] = 9; //Y
            vtx[vtxCtr+2] = wallRad*(float)Math.cos(ftnAngY*mDeg2Rad); //Z
        }

        StoreVertexData(gl, vtx, mWALL); //store in GPU buffer
    }

    void BuildDrop(GL11 gl)
    {
        //every drop has the same coordinates
        //we glRotate and glTranslate when drawing
        float vtx[] = {
                // X,  Y, Z
                0f, 0f, 0,
                -1f,-1f, 0,
                1f,-1f, 0
        };

        StoreVertexData(gl, vtx, mDROP); //store in GPU buffer
    }

    void BuildSplash(GL11 gl)
    {
        //splashes never move
        //all splash triangles stored together
        int triCnt = 6;
        int vtxCnt = mStreamCnt*9*triCnt;
        float[] vtx = new float[vtxCnt];
        int vtxCtr = 0;
        //for each stream
        for (float ftnAngY = 0;ftnAngY < 360;ftnAngY += 360/mStreamCnt)
        {
            //get coordinates of fountain drop (end of stream)
            float dropX = mArcRad*1.5f*(float)Math.sin(ftnAngY*mDeg2Rad);
            float dropZ = mArcRad*1.5f*(float)Math.cos(ftnAngY*mDeg2Rad);
            float mid = 0; //toggle for edge\middle vertex
            int triCtr = 0;
            //get angle for triangle edges and centers
            for (float sAngY = 0;sAngY < 360;sAngY += 360/(2*triCnt))
            {
                float realAngY = sAngY+ftnAngY; //shift angle to match stream angle
                //middle vertex have larger radius then edge vertices
                //use mid to toggle radius length
                float sX = (float)Math.sin(realAngY*mDeg2Rad)*(1+2*mid)+dropX;
                float sZ = (float)Math.cos(realAngY*mDeg2Rad)*(1+2*mid)+dropZ;

                vtx[vtxCtr] = sX;
                vtx[vtxCtr+1] = 0+mid*3; //Y, middle vertex is higher then edges
                vtx[vtxCtr+2] = sZ;

                if (mid%2==0) //edge vertex
                {
                    if (triCtr == 0) //first triangle for this drop, connect to last triangle in loop
                    {
                        vtx[vtxCtr+triCnt*9-3] = sX;
                        vtx[vtxCtr+triCnt*9-2] = 0; //Y
                        vtx[vtxCtr+triCnt*9-1] = sZ;
                    }
                    else //next triangle shares a corner
                    {
                        vtx[vtxCtr+3] = sX;
                        vtx[vtxCtr+4] = 0; //Y
                        vtx[vtxCtr+5] = sZ;
                        vtxCtr+=3; //we set 2 corners, so skip ahead
                    }
                    triCtr++; //keep track of which triangle we're creating
                }
                else
                if (triCtr == triCnt) vtxCtr+=3; //for loop skips last vtx
                vtxCtr+=3; //next corner
                mid = 1-mid; //toggle
            }
        }

        StoreVertexData(gl, vtx, mSPLASH); //store in GPU buffer
    }

    void StoreVertexData(GL11 gl, float[] pVertices, int pObjectNum)
    {
        FloatBuffer buffer = ByteBuffer.allocateDirect(pVertices.length * 4) //float is 4 bytes
                .order(ByteOrder.nativeOrder())// use the device hardware's native byte order
                .asFloatBuffer()  // create a floating point buffer from the ByteBuffer
                .put(pVertices);	// add the coordinates to the FloatBuffer

        (gl).glBindBuffer(GL11.GL_ARRAY_BUFFER, pObjectNum); //bind as current object
        buffer.position(0);
        //allocate memory and write buffer data
        (gl).glBufferData(GL11.GL_ARRAY_BUFFER, buffer.capacity()*4, buffer, GL11.GL_STATIC_DRAW);
        (gl).glBindBuffer(GL11.GL_ARRAY_BUFFER, 0); //unbind from buffer
        mBufferLen[pObjectNum] = buffer.capacity()/3; //store for drawing
    }

    //this is called when the user changes phone orientation (portrait\landscape)
    @Override
    public void onSurfaceChanged(GL10 gl, int pWidth, int pHeight)
    {
        gl.glViewport(0, 0, pWidth, pHeight); //the viewport is the screen
        // make adjustments for screen ratio, default would be stretched square
        mScrHeight = pHeight;
        mScrWidth = pWidth;
        mScrRatio = mScrWidth/mScrHeight;

        //set to projection mode to set up Frustum
        gl.glMatrixMode(GL11.GL_PROJECTION);		// set matrix to projection mode
        gl.glLoadIdentity();						// reset the matrix to its default state
        //calculate the clip region to minimize the depth buffer range (more precise)
        float camDist = (float)Math.sqrt(mCamXpos*mCamXpos+mCamYpos*mCamYpos+mCamZpos*mCamZpos);
        mClipStart = Math.max(2, camDist-185); //max scene radius is 185 points at corners
        //set up the perspective pyramid and clip points
        gl.glFrustumf(
                -mScrRatio*.5f*mClipStart,
                mScrRatio*.5f*mClipStart,
                -1f*.5f*mClipStart,
                1f*.5f*mClipStart,
                mClipStart,
                mClipStart+185+Math.min(185, camDist));

        //foreground objects are bigger and hide background objects
        gl.glEnable(GL11.GL_DEPTH_TEST);

        //set to ModelView mode to set up objects
        gl.glMatrixMode(GL11.GL_MODELVIEW);
        mOrientation = getResources().getConfiguration().orientation;
    }

    //this is called continuously
    @Override
    public void onDrawFrame(GL10 gl1)
    {
        GL11 gl = (GL11)gl1; //we need 1.1 functionality
        if (mResetMatrix) //camera distance changed
        {
            //recalc projection matrix and clip region
            onSurfaceChanged(gl, (int)mScrWidth, (int)mScrHeight);
            mResetMatrix = false;
        }

        //reset color and depth buffer
        gl.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        gl.glLoadIdentity();   //reset the matrix to its default state

        if (UseTiltAngle) //use phone tilt to determine X axis angle
        {
            //float hyp = (float)Math.sqrt(AccelY*AccelY+AccelZ*AccelZ);
            if (RotateScene) //rotate camera around 0,0,0
            {
                //calculate new X angle
                float HypLen = (float)Math.sqrt(mCamXpos*mCamXpos+mCamZpos*mCamZpos); //across floor
                mSceneXAng = 90-(float)Math.atan2(AccelY,AccelZ)*(float)mRad2Deg;
                // stop at 90 degrees or scene will go upside down
                if (mSceneXAng > 89.9) mSceneXAng = 89.9f;
                if (mSceneXAng < -89.9) mSceneXAng = -89.9f;

                float HypZLen = (float)Math.sqrt(mCamXpos*mCamXpos+mCamYpos*mCamYpos+mCamZpos*mCamZpos); //across floor
                //HypZLen stays same with new angle
                //move camera to match angle
                mCamYpos = HypZLen*(float)Math.sin(mSceneXAng*mDeg2Rad);
                float HypLenNew = HypZLen*(float)Math.cos(mSceneXAng*mDeg2Rad); //across floor
                mCamZpos *= HypLenNew/HypLen;
                mCamXpos *= HypLenNew/HypLen;
            }
            else //rotate camera
            {
                mCamXang = (float)Math.atan2(AccelY,AccelZ)*(float)mRad2Deg - 90;
                //don't let scene go upside down
                if (mCamXang > 89.9) mCamXang = 89.9f;
                if (mCamXang < -89.9) mCamXang = -89.9f;
                ChangeCameraAngle(0, 0); //set target position
            }
        }

        //gluLookAt tells openGL the camera position and view direction (target)
        //target is 0,0,0 for scene rotate
        //Y is up vector, so we set it to 100 (can be any positive number)
        GLU.gluLookAt(gl, mCamXpos, mCamYpos, mCamZpos, mTargetX, mTargetY, mTargetZ, 0f, 100.0f, 0.0f);


        //use clock to adjust animation angle for smoother motion
        //if frame takes longer, angle is greater and we catch up
        long now = SystemClock.elapsedRealtime();
        long diff = now - mLastTime;
        mLastTime = now;

        //if paused, animation angle does not change
        if (!Paused)
        {
            mAngCtr += diff/100.0;
            if (mAngCtr > 360) mAngCtr -= 360;
        }

        //draw the scene
        DrawSceneObjects(gl);

        if (ShowFPS) //average fps across last 20 frames
        {
            //elapsedRealtime() returns milliseconds since phone boot
            int thisFrameTime = (int)(SystemClock.elapsedRealtime()-mStartTime);
            //mFrameTime array stores times for last 20 frames
            mFPS = (mFrameTime.length)*1000f/(thisFrameTime-mFrameTime[mFramePos]);
            mFrameTime[mFramePos] = (int)(SystemClock.elapsedRealtime()-mStartTime);
            if (mFramePos < mFrameTime.length-1) //move pointer
                mFramePos++;
            else //end of array, jump to start
                mFramePos=0;
            if (++mFPSDispCtr == 10) //update fps display every 10 frames
            {
                mFPSDispCtr=0;
                SetStatusMsg(Math.round(mFPS*100)/100f+" fps"); //2 decimal places
            }
        }
    }
    //float splashCtr = 0;
    void DrawSceneObjects(GL11 gl)
    {
        if (ShowBall)
        {
            //draw first color
            gl.glPushMatrix();
            gl.glColor4f(.5f, .5f, .5f, 1); //gray
            gl.glRotatef(mAngCtr, 0.0f, 1.0f, 0f);
            DrawObject(gl, GL11.GL_TRIANGLES, mBALL);
            gl.glPopMatrix();

            //rotate by one slice and draw second color
            gl.glPushMatrix();
            gl.glColor4f(0.7f, 1f, 0.7f, 1f); //light green
            gl.glRotatef(mAngCtr+360f/mBallHSliceCnt, 0.0f, 1.0f, 0f);
            DrawObject(gl, GL11.GL_TRIANGLES, mBALL);
            gl.glPopMatrix();
        }

        if (ShowFountain)
        {
            DrawFountain(gl);
        }

        if (ShowPool) //pool and wall
        {
            gl.glColor4f(0.2f, 0.0f, 0.0f, 1f); //dark red
            DrawObject(gl, GL11.GL_TRIANGLE_STRIP, mWALL);
            gl.glColor4f(0.7f, 1f, 0.7f, 1f); //light green
            DrawObject(gl, GL11.GL_TRIANGLE_FAN, mPOOL);
        }

        if (ShowFountain && ShowPool) //splashes
        {
            gl.glPushMatrix(); //scale the splash triangles
            gl.glColor4f(.9f, 0.9f, 0.9f, 1f); //off-white
            gl.glTranslatef(0, 3, 0); //move splash to pool surface
            //the splash scales up then down
            gl.glScalef(1f, Math.abs((
                    mRepeatLen/2f-mAngCtr%(mRepeatLen))*0.4f), 1f); //scale Y only
            DrawObject(gl, GL11.GL_TRIANGLES, mSPLASH);
            gl.glPopMatrix();
        }

        if (ShowFloor)
        {
            gl.glColor4f(0.7f, 1f, 0.7f, 1f); //light green
            DrawObject(gl, GL11.GL_TRIANGLES, mFLOOR);
        }
    }

    void DrawObject(GL11 gl, int pShapeType, int pObjNum)
    {
        //activate vertex array type
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        //get vertices for this object id
        gl.glBindBuffer(GL11.GL_ARRAY_BUFFER, pObjNum);
        //each vertex is made up of 3 floats [x\y\z]
        gl.glVertexPointer(3, GL11.GL_FLOAT, 0, 0);
        //draw triangles
        gl.glDrawArrays(pShapeType, 0, mBufferLen[pObjNum]);
        //unbind from memory
        gl.glBindBuffer(GL11.GL_ARRAY_BUFFER, 0);
    }

    public void SetStatusMsg(String pMsg)
    {
        //mTagStore = this. We just need an object to pass text to the anonymous method
        mTagStore.setTag(pMsg);
        mThreadHandler.post(new Runnable() {
            public void run() {	mTxtMsg.setText(mTagStore.getTag().toString()); }
        });
    }

    //if user hides FPS, then clear text
    public void SetShowFPS(boolean pShowFPS)
    {
        ShowFPS = pShowFPS;
        SetStatusMsg(""); //clear message
    }

    //rotate scene or rotate camera
    public void SwapCenter()
    {
        RotateScene = !RotateScene;
        if (RotateScene) //rotate around fountain
        {
            //calculate scene angles based on camera position
            //hypotenuse using 2 dimensions
            float hypLen = (float)Math.sqrt(mCamXpos*mCamXpos+mCamZpos*mCamZpos); //across floor
            mSceneYAng = (float)Math.atan2(mCamXpos,mCamZpos)*(float)mRad2Deg;
            //3rd dimension
            mSceneXAng = (float)Math.atan2(mCamYpos,hypLen)*(float)mRad2Deg;

            mTargetX = mTargetY = mTargetZ = 0; //camera always looks at 0,0,0
        }
        else //rotate camera
        {
            //camera angle is reverse of scene angle
            mCamYang = mSceneYAng+180;
            mCamXang = -mSceneXAng;
            ChangeCameraAngle(0,0); //set camera view target
        }
    }

    //rotate camera around fountain
    void ChangeSceneAngle(float pChgXang, float pChgYang)
    {
        //hypotenuse using 2 dimensions
        float hypLen = (float)Math.sqrt(mCamXpos*mCamXpos+mCamZpos*mCamZpos); //across floor
        //process X and Y angles separately
        if (pChgYang != 0)
        {
            mSceneYAng += pChgYang;
            if (mSceneYAng < 0) mSceneYAng += 360;
            if (mSceneYAng > 360) mSceneYAng -= 360;
            //move camera according to new Y angle
            mCamXpos = hypLen*(float)Math.sin(mSceneYAng*mDeg2Rad);
            mCamZpos = hypLen*(float)Math.cos(mSceneYAng*mDeg2Rad);
        }

        if (pChgXang != 0)
        {
            //hypotenuse using all 3 dimensions
            float hypZLen = (float)Math.sqrt(hypLen*hypLen+mCamYpos*mCamYpos); // 0,0,0 to camera
            mSceneXAng += pChgXang;
            if (mSceneXAng > 89.9) mSceneXAng = 89.9f;
            if (mSceneXAng < -89.9) mSceneXAng = -89.9f;
            //hypZLen stays same with new angle
            //move camera according to new X angle
            mCamYpos = hypZLen*(float)Math.sin(mSceneXAng*mDeg2Rad);
            float HypLenNew = hypZLen*(float)Math.cos(mSceneXAng*mDeg2Rad); //across floor
            mCamZpos *= HypLenNew/hypLen;
            mCamXpos *= HypLenNew/hypLen;
        }
        //float camDist = (float)Math.sqrt(mCamXpos*mCamXpos+mCamYpos*mCamYpos+mCamZpos*mCamZpos);
        ///SetStatusMsg(""+camDist+" : "+mSceneXAng+" : "+mSceneYAng);
    }

    //change camera view direction
    void ChangeCameraAngle(float pChgXang, float pChgYang)
    {
        mCamXang += pChgXang;
        mCamYang += pChgYang;
        //keep angle within 360 degrees
        if (mCamYang > 360) mCamYang -= 360;
        if (mCamYang < 0) mCamYang += 360;
        //don't let view go upside down
        if (mCamXang > 89.9) mCamXang = 89.9f;
        if (mCamXang < -89.9) mCamXang = -89.9f;
        // move view target according to new angles
        mTargetY = mCamYpos+mViewRad*(float)Math.sin(mCamXang * mDeg2Rad);
        mTargetX = mCamXpos+mViewRad*(float)Math.cos(mCamXang * mDeg2Rad)*(float)Math.sin(mCamYang * mDeg2Rad);
        mTargetZ = mCamZpos+mViewRad*(float)Math.cos(mCamXang * mDeg2Rad)*(float)Math.cos(mCamYang * mDeg2Rad);
    }

    void MoveCamera(float pDist)
    {
        //move camera along line of sight toward target vertex
        if (RotateScene) //move towards\away from 0,0,0
        {
            //distance from 0,0,0
            float curdist = (float)Math.sqrt(
                    mCamXpos*mCamXpos +
                            mCamYpos*mCamYpos +
                            mCamZpos*mCamZpos);
            //if camera will pass center than reduce distance
            if (pDist<0 && curdist + pDist < 0.01) //can't go to exact center
                pDist = 0.01f-curdist;//0.01 closest distance
            float ratio = pDist/curdist;
            float chgCamX = (mCamXpos)*ratio;
            float chgCamY = (mCamYpos)*ratio;
            float chgCamZ = (mCamZpos)*ratio;
            mCamXpos += chgCamX;
            mCamYpos += chgCamY;
            mCamZpos += chgCamZ;
        }
        else //move towards\away from target
        {
            //mViewRad is 100, so do percentage
            float ratio = pDist/mViewRad;
            float chgCamX = (mCamXpos-mTargetX)*ratio;
            float chgCamY = (mCamYpos-mTargetY)*ratio;
            float chgCamZ = (mCamZpos-mTargetZ)*ratio;
            mCamXpos += chgCamX;
            mCamYpos += chgCamY;
            mCamZpos += chgCamZ;
            mTargetX += chgCamX;
            mTargetY += chgCamY;
            mTargetZ += chgCamZ;
        }
        ///float camDist = (float)Math.sqrt(mCamXpos*mCamXpos+mCamYpos*mCamYpos+mCamZpos*mCamZpos);
        ///SetStatusMsg(""+camDist+" : "+mSceneXAng+" : "+mSceneYAng);
        mResetMatrix = true; //recalc depth buffer range
    }

    public boolean onTouchEvent(final MotionEvent pEvent)
    {
        if (pEvent.getAction() == MotionEvent.ACTION_DOWN) //start drag
        {
            //store start position
            mDragStartX = pEvent.getX();
            mDragStartY = pEvent.getY();
            mDownX = pEvent.getX();
            mDownY = pEvent.getY();
            return true; //must have this
        }
        else if (pEvent.getAction() == MotionEvent.ACTION_UP) //drag stop
        {
            //if user did not move more than 5 pixels, assume screen tap
            if ((Math.abs(mDownX - pEvent.getX()) <= 5) && (Math.abs(mDownY - pEvent.getY()) <= 5))
            {
                if (pEvent.getY() < mScrHeight/2.0) //top half of screen
                    MoveCamera(-5); //move camera forward
                else if (pEvent.getY() > mScrHeight/2.0) //bottom half of screen
                    MoveCamera(5); //move camera back
            }
            return true; //must have this
        }
        else if (pEvent.getAction() == MotionEvent.ACTION_MOVE) //dragging
        {
            //to prevent constant recalcs, only process after 5 pixels
            //if user moves less than 5 pixels, we assume screen tap, not drag
            //we divide by 3 to slow down scene rotate
            if (Math.abs(pEvent.getX() - mDragStartX) > 5) //process Y axis rotation
            {
                if (RotateScene) //rotate around fountain
                    ChangeSceneAngle(0, (mDragStartX - pEvent.getX())/3f); //Y axis
                else //rotate camera
                    ChangeCameraAngle(0, (mDragStartX - pEvent.getX())/3f); //Y axis
                mDragStartX = pEvent.getX();
            }
            if (Math.abs(pEvent.getY() - mDragStartY) > 5) //process X axis rotation
            {
                if (RotateScene) //rotate around fountain
                    ChangeSceneAngle((pEvent.getY() - mDragStartY)/3f, 0); //X axis
                else //rotate camera
                    ChangeCameraAngle((mDragStartY - pEvent.getY())/3f, 0); //X axis
                mDragStartY = pEvent.getY();
            }
            return true; //must have this
        }
        return super.onTouchEvent(pEvent);
    }

    void DrawFountain(GL11 gl)
    {
        //get billboard angles for 0,0,0
        //calculate angle from 0,0,0 to camera, used if single billboard
        float angY = 270-(float)Math.atan2(mCamZpos,mCamXpos)*(float)mRad2Deg; //around Y axis

        float hypLen = (float)Math.sqrt(mCamXpos*mCamXpos+mCamZpos*mCamZpos); //across floor
        float angX = (float)Math.atan2(mCamYpos,hypLen)*(float)mRad2Deg; //X axis

        int dropCtr = 0;
        //rotate around fountain center
        for (float ftnAngY = 0;ftnAngY < 360;ftnAngY += 360/mStreamCnt)
        {
            //draw each arc
            //arcAng will cycle through single segment and repeat
            float arcAng = mAngCtr%(mRepeatLen);
            for (;arcAng < 180;arcAng += mRepeatLen)
            {
                //default arc is half circle
                //use 0.75 to reduce arc width
                float dropRad = 0.75f*(mArcRad-mArcRad*(float)Math.cos(arcAng*mDeg2Rad));
                //use 1.5 to increase arc height
                dropCoords[dropCtr][1] = 1.5f*mArcRad*(float)Math.sin(arcAng*mDeg2Rad); //Y
                dropCoords[dropCtr][0] = dropRad*(float)Math.sin(ftnAngY*mDeg2Rad); //X
                dropCoords[dropCtr][2] = dropRad*(float)Math.cos(ftnAngY*mDeg2Rad); //Z
                dropCtr++;
            }
        }
        gl.glColor4f(0.5f, 0.5f, 1f, 1f); //light blue
        DrawDropTriangles(gl, angX, angY, dropCoords); //draw all triangles at once
    }

    //each triangle has the same dimensions, only location and rotation are different
    void DrawDropTriangles(GL11 gl, float pAngX, float pAngY, float[][] pDropCoords)
    {
        //DropCoords array only contains top vertex of each drop triangle
        //for each triangle, just translate to top vertex and redraw same triangle each time
        int TriCnt = pDropCoords.length; //triangle count
        //initialize vertex Buffer for triangle
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glBindBuffer(GL11.GL_ARRAY_BUFFER, mDROP);
        gl.glVertexPointer(3, GL11.GL_FLOAT, 0, 0);

        for (int ctr = 0;ctr < TriCnt;ctr++)
        {
            gl.glPushMatrix(); //translate\rotate only affects this single triangle
            gl.glTranslatef(pDropCoords[ctr][0], pDropCoords[ctr][1], pDropCoords[ctr][2]);
            if (MultiBillboard) //calc each triangle billboard angle separately
            {
                float hypLen = 0;
                float distX = mCamXpos-pDropCoords[ctr][0];
                float distY = mCamYpos-pDropCoords[ctr][1];
                float distZ = mCamZpos-pDropCoords[ctr][2];

                //hypotenuse in 2D
                hypLen = (float)Math.sqrt(distX*distX+distZ*distZ); //across floor
                pAngY = 270-(float)Math.atan2(distZ,distX)*(float)mRad2Deg;
                //3rd dimension
                pAngX = (float)Math.atan2(distY,hypLen)*(float)mRad2Deg;
            }
            gl.glRotatef(pAngY, 0, 1, 0);
            gl.glRotatef(pAngX, 1, 0, 0);
            gl.glDrawArrays(GL11.GL_TRIANGLES, 0, mBufferLen[mDROP]); //single drop
            gl.glPopMatrix(); //done with this triangle
        }
        gl.glBindBuffer(GL11.GL_ARRAY_BUFFER, 0); //unbind from buffer
    }

}
