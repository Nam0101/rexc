package dev.namnv.rexc.presentation.home

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Opens the Play Services code scanner (its own UI, no camera permission) and hands back the
 * server it read. Cancelling is silent; anything else goes to [onError] as one line.
 */
fun scanHostQr(context: Context, onResult: (HostQr) -> Unit, onError: (String) -> Unit) {
  val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
  GmsBarcodeScanning.getClient(context, options).startScan()
    .addOnSuccessListener { code ->
      parseHostQr(code.rawValue.orEmpty())?.let(onResult) ?: onError("That code is not a Rex server")
    }
    .addOnFailureListener { e ->
      if ((e as? MlKitException)?.errorCode != MlKitException.CODE_SCANNER_CANCELLED) {
        onError(e.message ?: "Could not open the scanner")
      }
    }
}
