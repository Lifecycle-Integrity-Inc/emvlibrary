package com.wwopenfare.emvlibrary

/**
 * Constructs a [ByteArray] from plain [Int] hex literals,
 * avoiding repetitive [toByte] casts throughout the EMV library.
 *
 * Usage:
 *   hexBytes(0x9F, 0x38)          // two-byte EMV tag code
 *   hexBytes(0xA0, 0x00, 0x00, 0x00, 0x03)  // AID prefix
 */
internal fun hexBytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }
