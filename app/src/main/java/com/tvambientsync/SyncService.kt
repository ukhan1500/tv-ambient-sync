package com.tvambientsync

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.bluetooth.*
import android.media.ImageReader
import android.media.projection.*
import android.hardware.display.*
import android.graphics.PixelFormat
import java.util.UUID

@Suppress("DEPRECATION", "MissingPermission")
class SyncService : Service() {
 private val h = Handler(Looper.getMainLooper())
 private var projection: MediaProjection? = null
 private var reader: ImageReader? = null
 private var display: VirtualDisplay? = null
 private var gatt: BluetoothGatt? = null
 private var characteristic: BluetoothGattCharacteristic? = null
 private var busy = false
 private var connecting = false
 private var address = ""
 private var serviceId = ""
 private var characteristicId = ""
 private var lastFrame = 0L
 private var lastWrite = 0L
 private var smooth: DoubleArray? = null
 private var sent = intArrayOf(-255,-255,-255)
 private var interval = 50L
 private var dominantBin = -1
 private var diagnosticStart = 0L
 private var diagnosticEnd = 0L
 private var diagnosticLast = 0L
 private val diagnosticLines = mutableListOf<String>()
 private val finishDiagnostic = Runnable {
  val text=if(diagnosticLines.isEmpty()) "No frames received during recording. Video capture may be unavailable." else diagnosticLines.joinToString("\n")
  getSharedPreferences("diagnostics",0).edit().putString("result",text).apply()
  diagnosticStart=0L
 }
 private val diagnosticReceiver=object: BroadcastReceiver() {
  override fun onReceive(context: Context?, intent: Intent?) {
   h.removeCallbacks(finishDiagnostic)
   diagnosticLines.clear()
   diagnosticLast=0L
   diagnosticStart=SystemClock.elapsedRealtime()+10000
   diagnosticEnd=diagnosticStart+10000
   getSharedPreferences("diagnostics",0).edit().putString("result","Waiting 10 seconds, then recording for 10 seconds. Return to the video now.").apply()
   h.postDelayed(finishDiagnostic,20000)
  }
 }
 private var vivid = false
 private var lastStatus = 0L
 private var brightness = 0.8
 private var running = false
 private val reconnect = Runnable { if(running) connect() }
 private val brightnessReceiver=object: BroadcastReceiver() {
  override fun onReceive(context: Context?, intent: Intent?) {
   brightness=(intent?.getIntExtra("value",80) ?: 80).coerceIn(0,100)/100.0
   sent=intArrayOf(-255,-255,-255)
  }
 }
 override fun onCreate() {
  super.onCreate()
  val diagnosticsFilter=IntentFilter("com.tvambientsync.DIAGNOSTICS")
  if(Build.VERSION.SDK_INT>=33) registerReceiver(diagnosticReceiver,diagnosticsFilter,Context.RECEIVER_NOT_EXPORTED)
  else registerReceiver(diagnosticReceiver,diagnosticsFilter)
  val filter=IntentFilter("com.tvambientsync.BRIGHTNESS")
  if(Build.VERSION.SDK_INT>=33) registerReceiver(brightnessReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
  else registerReceiver(brightnessReceiver,filter)
 }
 override fun onBind(intent: Intent?) = null
 private fun status(text: String) {
  getSharedPreferences("sync",0).edit().putString("status",text).apply()
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  if(intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
  if(running) return START_NOT_STICKY
  val token = intent?.getParcelableExtra<Intent>("token")
  if(token == null) { stopSelf(); return START_NOT_STICKY }
  try {
   val nm = getSystemService(NotificationManager::class.java)
   nm.createNotificationChannel(NotificationChannel("sync","Screen synchronization",NotificationManager.IMPORTANCE_LOW))
   val stop = PendingIntent.getService(this,1,Intent(this,SyncService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
   val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
   val notification = Notification.Builder(this,"sync").setSmallIcon(android.R.drawable.ic_menu_view)
    .setContentTitle("TV Ambient Sync").setContentText("Screen color sync active").setContentIntent(open)
    .addAction(Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build()
   if(Build.VERSION.SDK_INT >= 29) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
   else startForeground(1,notification)
   running=true
   address=intent.getStringExtra("address")!!
   serviceId=intent.getStringExtra("service")!!
   characteristicId=intent.getStringExtra("characteristic")!!
   interval=intent.getLongExtra("interval",50).coerceIn(50,1000)
   vivid=intent.getBooleanExtra("vivid",false)
   brightness=intent.getDoubleExtra("brightness",0.8).coerceIn(0.0,1.0)
   projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,token)
   projection!!.registerCallback(object: MediaProjection.Callback() { override fun onStop() { status("Screen capture stopped; grant consent to restart."); stopSelf() } },h)
   val metrics=resources.displayMetrics
   val width=160
   val height=(width.toDouble()*metrics.heightPixels/metrics.widthPixels).toInt().coerceAtLeast(1)
   reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
   reader!!.setOnImageAvailableListener({ source ->
    val frame=source.acquireLatestImage()
    if(frame != null) {
     try {
      val now=SystemClock.elapsedRealtime()
      if(now-lastFrame >= interval) {
       lastFrame=now
       val p=frame.planes[0]; val b=p.buffer
       // Group chromatic pixels into 12 hue families; neutrals into four brightness groups.
       // Every sampled pixel votes equally, including black.
       val votes=IntArray(16)
       val sums=Array(16) { LongArray(3) }
       val hsv=FloatArray(3)
       var count=0
       for(y in 0 until frame.height step 2) for(x in 0 until frame.width step 2) {
        val offset=y*p.rowStride+x*p.pixelStride
        val r=b.get(offset).toInt() and 255
        val g=b.get(offset+1).toInt() and 255
        val blue=b.get(offset+2).toInt() and 255
        android.graphics.Color.RGBToHSV(r,g,blue,hsv)
        val bin=if(hsv[1]<0.20f || hsv[2]<0.08f) 12+(hsv[2]*4).toInt().coerceIn(0,3)
          else (((hsv[0]+15f)%360f)/30f).toInt().coerceIn(0,11)
        votes[bin]++
        sums[bin][0]+=r; sums[bin][1]+=g; sums[bin][2]+=blue
        count++
       }
       val winner=votes.indices.maxByOrNull { votes[it] } ?: 0
       // Hold the existing family only for near ties (under five percentage points).
       if(dominantBin<0 || votes[dominantBin]==0 ||
          votes[winner]>votes[dominantBin]+count*0.05) dominantBin=winner
       val target=DoubleArray(3) { sums[dominantBin][it].toDouble()/votes[dominantBin].coerceAtLeast(1) }
       val share=100*votes[dominantBin]/count.coerceAtLeast(1)
       if(vivid) {
        val gray=target.average()
        for(k in 0..2) target[k]=(gray+(target[k]-gray)*1.8).coerceIn(0.0,255.0)
       }
       val current=smooth ?: target.copyOf()
       for(k in 0..2) current[k]+=(if(interval<=50) 1.0 else if(interval<=100) 0.8 else 0.35)*(target[k]-current[k])
       smooth=current
       val rgb=IntArray(3) { (current[it]*brightness).toInt().coerceIn(0,255) }
       if(diagnosticStart>0 && now>=diagnosticStart && now<diagnosticEnd && now-diagnosticLast>=1000) {
        diagnosticLast=now
        val neutral=100*(12..15).sumOf { votes[it] }/count.coerceAtLeast(1)
        diagnosticLines.add("+${(now-diagnosticStart)/1000}s: dominant ${share}% raw ${target.map { it.toInt() }.joinToString()} output ${rgb.joinToString()} neutral ${neutral}% BLE ${if(characteristic!=null) "ready" else "connecting"}")
       }
       if(rgb.indices.any { kotlin.math.abs(rgb[it]-sent[it]) >= 4 } || now-lastWrite>2000) write(rgb)
       if(now-lastStatus>=1000) { lastStatus=now; status(if(target.all { it<3 }) "Sync active: black frame (dark or protected content)" else "Dominant $share% • RGB ${rgb.joinToString()} • BLE ${if(characteristic!=null) "ready" else "connecting"}") }
      }
     } catch(e: Exception) { status("Frame error: ${e.message}") } finally { frame.close() }
    }
   },h)
   display=projection!!.createVirtualDisplay("Ambient",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,h)
   connect()
  } catch(e: Exception) { status("Cannot start sync: ${e.message}"); stopSelf() }
  return START_NOT_STICKY
 }
 private fun connect() {
  if(connecting || !running) return
  connecting=true
  try {
   val adapter=getSystemService(BluetoothManager::class.java).adapter
   gatt=adapter.getRemoteDevice(address).connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE)
   h.postDelayed({ if(running && characteristic==null) retry() },15000)
  } catch(e: Exception) { status("BLE: ${e.message}"); retry() }
 }
 private fun retry() {
  h.removeCallbacks(reconnect)
  gatt?.close(); gatt=null; characteristic=null; busy=false; connecting=false
  if(running) h.postDelayed(reconnect,3000)
 }
 private val callback=object: BluetoothGattCallback() {
  override fun onConnectionStateChange(g: BluetoothGatt,status: Int,state: Int) { h.post {
   if(g==gatt && running) {
    if(status==0 && state==BluetoothProfile.STATE_CONNECTED) g.discoverServices() else retry()
   }
  } }
  override fun onServicesDiscovered(g: BluetoothGatt,status: Int) { h.post {
   if(g==gatt && running) {
    characteristic=if(status==0) g.getService(UUID.fromString(serviceId))?.getCharacteristic(UUID.fromString(characteristicId)) else null
    if(characteristic==null) retry() else { connecting=false; sent=intArrayOf(-255,-255,-255) }
   }
  } }
  override fun onCharacteristicWrite(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int) { h.post { if(g==gatt) { busy=false; if(status!=0) retry() } } }
 }
 private fun write(rgb: IntArray) {
  val g=gatt ?: return; val c=characteristic ?: return
  if(busy) { if(SystemClock.elapsedRealtime()-lastWrite>2000) retry(); return }
  c.writeType=if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
  c.value=byteArrayOf(0x7e,0,5,3,rgb[0].toByte(),rgb[1].toByte(),rgb[2].toByte(),0,0xef.toByte())
  busy=true; lastWrite=SystemClock.elapsedRealtime()
  if(g.writeCharacteristic(c)) sent=rgb.copyOf() else retry()
 }
 override fun onDestroy() {
  unregisterReceiver(diagnosticReceiver)
  if(diagnosticStart>0) getSharedPreferences("diagnostics",0).edit().putString("result","Sync stopped before completion.\n"+diagnosticLines.joinToString("\n")).apply()
  unregisterReceiver(brightnessReceiver)
  running=false; h.removeCallbacksAndMessages(null)
  display?.release(); reader?.close(); projection?.stop()
  try { gatt?.close() } catch(_: Exception) {}
  stopForeground(STOP_FOREGROUND_REMOVE)
  super.onDestroy()
 }
}
