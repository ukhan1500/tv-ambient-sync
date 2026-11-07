package com.tvambientsync

import android.Manifest
import android.app.Activity
import android.os.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.widget.*

@Suppress("DEPRECATION")
class MainActivity : Activity() {
 private val handler = Handler(Looper.getMainLooper())
 private lateinit var layout: LinearLayout
 private lateinit var log: TextView
 private val adapter get() = getSystemService(BluetoothManager::class.java).adapter
 private var gatt: BluetoothGatt? = null
 private var selected: BluetoothGattCharacteristic? = null
 private val seen = mutableSetOf<String>()
 private var busy = false
 private var deviceAddress = ""
 private var interval = 50L
 private var vivid = false
 private var brightness = 0.8
 private fun report(s: String) = runOnUiThread { log.append("\n$s") }
 private fun button(title: String, action: () -> Unit) {
  layout.addView(Button(this).apply { text = title; setOnClickListener { try { action() } catch(e: Exception) { report(e.toString()) } } })
 }
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  layout = LinearLayout(this).apply { orientation = 1; setPadding(48,24,48,24) }
  setContentView(ScrollView(this).apply { addView(layout) })
  log = TextView(this).apply { text = "TV Ambient Sync • v0.6\nExperimental protocol. Select a writable characteristic before testing." }
  layout.addView(log)
  button("Record video diagnostics (10s delay, 10s recording)") {
   if(getSharedPreferences("sync",0).getBoolean("running",false)) {
    sendBroadcast(android.content.Intent("com.tvambientsync.DIAGNOSTICS").setPackage(packageName))
    report("Return to your video now. Wait 20 seconds before coming back.")
   } else report("Start Sync first.")
  }
  button("Show saved video diagnostics") {
   android.app.AlertDialog.Builder(this).setTitle("Saved video samples")
    .setMessage(getSharedPreferences("diagnostics",0).getString("result","No recording yet. Start sync, then record diagnostics."))
    .setPositiveButton("Close",null).show()
  }
  button("Start Sync") { startSync() }
  button("Stop Sync") { stopService(android.content.Intent(this,SyncService::class.java)); report("Sync stopped") }
  button("Sync status") { report(getSharedPreferences("sync",0).getString("status","Not started") ?: "Not started") }
  button("Speed: toggle instant 50 / smooth 250 ms") { interval=if(interval==50L) 250L else 50L; report("Next session interval: $interval ms") }
  button("Color enhancement: toggle on/off") { vivid=!vivid; report("Next session enhanced colors: $vivid") }
  brightness=getSharedPreferences("settings",0).getInt("brightness",80)/100.0
  val brightnessLabel=TextView(this).apply { text="Brightness: ${(brightness*100).toInt()}% — use Left / Right" }
  layout.addView(brightnessLabel)
  layout.addView(SeekBar(this).apply {
   max=100
   progress=getSharedPreferences("settings",0).getInt("brightness",80)
   keyProgressIncrement=1
   minimumHeight=64
   contentDescription="LED brightness, 0 to 100 percent"
   setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
     brightness=value/100.0
     brightnessLabel.text="Brightness: $value% — use Left / Right"
     if(fromUser) {
      getSharedPreferences("settings",0).edit().putInt("brightness",value).apply()
      sendBroadcast(android.content.Intent("com.tvambientsync.BRIGHTNESS").setPackage(packageName).putExtra("value",value))
     }
    }
    override fun onStartTrackingTouch(bar: SeekBar?) {}
    override fun onStopTrackingTouch(bar: SeekBar?) {}
   })
  })
  button("Scan for LEDs (15 seconds)") { scan() }
  button("Disconnect") { disconnect() }
  listOf("Red" to intArrayOf(255,0,0), "Green" to intArrayOf(0,255,0), "Blue" to intArrayOf(0,0,255), "White" to intArrayOf(255,255,255), "Off" to intArrayOf(0,0,0)).forEach { (name,rgb) ->
   button("Experimental $name") { send(rgb) }
  }
 }
 private fun startSync() {
  if(selected == null && getSharedPreferences("controller",0).getString("address",null)==null) { report("Connect and select the tested FFF3 characteristic once."); return }
  val manager=getSystemService(android.media.projection.MediaProjectionManager::class.java)
  val consent=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
  startActivityForResult(consent,22)
 }
 override fun onActivityResult(requestCode: Int,resultCode: Int,data: android.content.Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode!=22) return
  if(resultCode!=RESULT_OK || data==null) { report("Screen capture permission declined"); return }
  val prefs=getSharedPreferences("controller",0)
  val c=selected
  val savedAddress=if(c!=null) deviceAddress else prefs.getString("address","")!!
  val savedService=c?.service?.uuid?.toString() ?: prefs.getString("service","")!!
  val savedCharacteristic=c?.uuid?.toString() ?: prefs.getString("characteristic","")!!
  val request=android.content.Intent(this,SyncService::class.java)
   .putExtra("token",data).putExtra("address",savedAddress)
   .putExtra("service",savedService).putExtra("characteristic",savedCharacteristic)
   .putExtra("vivid",vivid)
   .putExtra("interval",interval).putExtra("brightness",brightness)
  disconnect()
  startForegroundService(request)
  report("Starting sync. Press Home and open other apps. Use Sync status to check progress.")
 }
 private fun permissions() = if(Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
 private fun scan() {
  if(permissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestPermissions(permissions(),1); report("Grant permissions, then press Scan again."); return }
  if(adapter == null || !adapter.isEnabled) { report("Enable Bluetooth in Google TV settings."); return }
  adapter.bluetoothLeScanner.stopScan(scanner)
  seen.clear()
  adapter.bluetoothLeScanner.startScan(scanner)
  report("Scanning…")
  handler.postDelayed({ adapter?.bluetoothLeScanner?.stopScan(scanner); report("Scan finished") },15000)
 }
 private val scanner = object: ScanCallback() {
  override fun onScanFailed(code: Int) { report("Scan failed: $code") }
  override fun onScanResult(type: Int, result: ScanResult) {
   val device = result.device
   if(!seen.add(device.address)) return
   runOnUiThread { button("Connect: ${result.scanRecord?.deviceName ?: device.name ?: "Unnamed"} • ${device.address}") {
    adapter.bluetoothLeScanner.stopScan(this)
    disconnect()
    deviceAddress=device.address
    report("Connecting ${device.address}")
    gatt = device.connectGatt(this@MainActivity,false,callback,BluetoothDevice.TRANSPORT_LE)
    handler.postDelayed({ if(gatt == null) { report("Connection/discovery timeout; reconnect if needed.") } },20000)
   } }
  }
 }
 private val callback = object: BluetoothGattCallback() {
  override fun onConnectionStateChange(g: BluetoothGatt,status: Int,state: Int) {
   if(g != gatt) return
   report("GATT status=$status state=$state")
   if(status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
   else if(state == BluetoothProfile.STATE_DISCONNECTED || status != 0) { selected=null; busy=false; g.close(); gatt=null }
  }
  override fun onServicesDiscovered(g: BluetoothGatt,status: Int) {
   if(g != gatt) return
   report("Discovery status=$status")
   if(status != 0) return
   g.services.forEach { service ->
    report("Service ${service.uuid}")
    service.characteristics.forEach { c ->
     report("Characteristic ${c.uuid} properties=${c.properties}")
     if(c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
      runOnUiThread { button("Use experimental write: ${c.uuid}") { if(g == gatt) { selected=c; getSharedPreferences("controller",0).edit().putString("address",deviceAddress).putString("service",c.service.uuid.toString()).putString("characteristic",c.uuid.toString()).apply(); report("Saved controller and selected ${c.uuid}") } } }
    }
   }
  }
  override fun onCharacteristicWrite(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int) { busy=false; report("Write result=$status (does not prove LEDs changed)") }
 }
 private fun send(rgb: IntArray) {
  val g = gatt ?: return report("Connect first")
  val c = selected ?: return report("Select a writable characteristic first")
  if(busy) return report("Wait for previous write")
  val bytes = byteArrayOf(0x7e,0,5,3,rgb[0].toByte(),rgb[1].toByte(),rgb[2].toByte(),0,0xef.toByte())
  c.writeType = if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
  c.value=bytes
  busy=true
  if(!g.writeCharacteristic(c)) { busy=false; report("Write rejected") }
  else report("Sent " + bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) })
  handler.postDelayed({ busy=false },1500)
 }
 private fun disconnect() { selected=null; busy=false; gatt?.close(); gatt=null; report("Disconnected") }
 override fun onDestroy() {
  handler.removeCallbacksAndMessages(null)
  try { adapter?.bluetoothLeScanner?.stopScan(scanner); gatt?.close() } catch(_: SecurityException) {}
  super.onDestroy()
 }
}
