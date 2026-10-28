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
 private fun report(s: String) = runOnUiThread { log.append("\n$s") }
 private fun button(title: String, action: () -> Unit) {
  layout.addView(Button(this).apply { text = title; setOnClickListener { try { action() } catch(e: Exception) { report(e.toString()) } } })
 }
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  layout = LinearLayout(this).apply { orientation = 1; setPadding(48,24,48,24) }
  setContentView(ScrollView(this).apply { addView(layout) })
  log = TextView(this).apply { text = "TV Ambient Sync • Phase 1\nExperimental protocol. Select a writable characteristic before testing." }
  layout.addView(log)
  button("Scan for LEDs (15 seconds)") { scan() }
  button("Disconnect") { disconnect() }
  listOf("Red" to intArrayOf(255,0,0), "Green" to intArrayOf(0,255,0), "Blue" to intArrayOf(0,0,255), "White" to intArrayOf(255,255,255), "Off" to intArrayOf(0,0,0)).forEach { (name,rgb) ->
   button("Experimental $name") { send(rgb) }
  }
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
    report("Connecting ${device.address}")
    gatt = device.connectGatt(this@MainActivity,false,callback,BluetoothDevice.TRANSPORT_LE)
    handler.postDelayed({ if(selected == null) { report("Connection/discovery timeout; reconnect if needed.") } },20000)
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
      runOnUiThread { button("Use experimental write: ${c.uuid}") { if(g == gatt) { selected=c; report("Selected ${c.uuid}") } } }
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
