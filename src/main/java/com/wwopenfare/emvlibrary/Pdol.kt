package com.wwopenfare.emvlibrary

import android.util.Log
import java.lang.Math.min
import java.util.*

const val LOG_PDOL_TAG = "MAKE_PDOL"
class CardPdolItem(val cardPdolItemValue: Int, val cardPdolIemLen: Int, val cardType: CardType) {
    val termPdolItem: ByteArray = getItemBytes()

    companion object {

        val pdolMap: Map<Int, ByteArray> = mapOf (
            // 9f66 ttq - moved to functin, cardType- dependent
            0x95 to byteArrayOf(0x80.toByte(),0x00,0x00,0x00,0x00), // TVR - Book 3 table 45
            0x9f02 to byteArrayOf(0x00,0x00,0x00,0x00,0x12,0x00),   // amount numeric
            0x9f03 to byteArrayOf(0x00,0x00,0x00,0x00,0x00,0x00),   // amount other
            // 0x9a to byteArrayOf(0x22,0x01,0x07),                     // trans date yymmdd
            0x9c to byteArrayOf(0x00),                              // trans type ISO 8583 Book B - 0x00 - purchase, 0x20 - refund
            0x9f01 to byteArrayOf(0x00,0x00,0x40,0x08,0x09,0x01),   // acc id
            0x9f09 to byteArrayOf(0x00,0x00),                       // app ver num
            0x9f15 to byteArrayOf(0x55,0x42),                       // MCC 5542 -general store
            0x9f16 to "FEDCBA012345678".toByteArray(charset=Charsets.US_ASCII), // length 0x0f - MID
            0x9f1a to byteArrayOf(0x01,0x24),               // country code numeric ISO 3166-1 Canada - 124
            0x9f1c to "00120012".toByteArray(charset=Charsets.US_ASCII), // terminal id
            0x9f1e to "80120012".toByteArray(charset=Charsets.US_ASCII), // terminal IFD
            0x5f2a  to byteArrayOf(0x01,0x24),                      // currency code numeric CAD = 124 ISO 4217 978 - EUR
            0x9f33 to byteArrayOf(0x00,0x08,0x40),                     // terminal capabilities Annex A.2 of [EMV Book 4].
            0x9f34 to byteArrayOf(0x3F,0x00,0x02),                     // CVM results
            0x9f35 to byteArrayOf(0x00),                               // terminal type book 4 table 37 - reference ISO 8583 ??
            // replaced with function 0x9f37              // unpredictable number
            0x9f39 to byteArrayOf(0x91.toByte()),         //POS Entry mode - ISO 8583 91 - NFC magstripe, 07 - NFC EMV

            //merchant id and location, length  0x20
            0x9f4e to "My Good Stuff at Main St Nanches".toByteArray(charset=Charsets.US_ASCII),
            //0x9f45 to byteArrayOf(0x01,0x00), // Signed Static Application Data
            //0x9f4c to byteArrayOf(0,0,0,0,0,0,0,0) // ICC dynamic number
        )
        fun toHex(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }
        fun nnToHex(nn: Int): Byte {
            val n0: Int = nn / 10 // n.
            val n1: Int = nn % 10 // .n
            return (n0 * 16 + n1).toByte()
        }
        fun rand4Bytes(): ByteArray {
            val ret = ByteArray(4)
            for (x in 0..3) {
                ret[x] = (255.9999* Math.random()).toInt().toUByte().toByte()
            }
            return ret
        }
        /*private fun submitDate() : ByteArray {
            // insert transaction date
            val ret = ByteArray(3)
            val cal = Calendar.getInstance()
            val year:Int = cal.get(Calendar.YEAR)-2000
            val yyHex = nnToHex(year)
            val month:Int = cal.get(Calendar.MONTH)+1
            val mmHex = nnToHex(month)
            val day:Int = cal.get(Calendar.DAY_OF_MONTH)
            val ddHex = nnToHex(day)
            ret[0]=yyHex //year yy
            ret[1]=mmHex//month mm
            ret[2]=ddHex  //day dd
            return ret
        }*/
    } // companion object

    private fun getItemBytes() : ByteArray {
        val ret = ByteArray(cardPdolIemLen) { 0 } // all zeroes
        var candidate = pdolMap[cardPdolItemValue] ?: ByteArray(0)
        Log.d(LOG_PDOL_TAG, "\nmapping ${cardPdolItemValue.toString(16)} to array ${toHex(candidate)} ")
        if (candidate.isEmpty()) candidate =
            when (cardPdolItemValue) {
                0x9a -> submitDate()
                0x9f37 -> rand4Bytes()
                0x9f66 -> ttq()
                else -> ByteArray(0)
            }
        if (candidate.isNotEmpty()) { // we need to fill in ret with good data
            val fillLen =
                ret.size.coerceAtMost(candidate.size) // JUst in case. Both are supposed to be equal
            for (i in 0 until fillLen) ret[i] = candidate[i]
        }
        return ret
    }

    private fun ttq():  ByteArray {
        return byteArrayOf(
            // 4 bytes
            // https://www.eftlab.com/knowledge-base/145-emv-nfc-tags/
            // 9F66 - TTQ term tx qualifiers - Book A Table 5-4, for Visa - Kernel 3, Section 3.3
            // https://stackoverflow.com/questions/50293650/not-getting-afl-for-visa-contactless-application
            //byteArrayOf(0x23.toByte(),0xe0.toByte(),0x40,0x00),
            when (cardType) {
                CardType.INTERAC -> 29 // does not seem to be used
                //  0 -magstr NOT supported
                //  0 - RFU
                //  1 - EMV supported
                //  0 - Contact NOT supported
                //  1 - online NOT capable reader
                //  0 - online PIN NOT supported
                //  0 - signature NOT supported
                //  1 - DDA supported
                else -> 0xB7.toByte()
                //  1 -magstr supported
                //  0 - RFU
                //  1 - EMV supported
                //  1 - Contact supportdr
                //  0 - online capable reader
                //  1 - online PIN supported
                //  1 - signature supported
                //  1 - DDA supported
                },
                0x10.toByte(),  // byte 2 bits 8-7 must be zero at the beginning of the transaction
                0x40,
                0x00
            )
    }

    private fun submitDate() : ByteArray {
        // insert transaction date
        val ret = ByteArray(3)
        val cal = Calendar.getInstance()
        val year:Int = cal.get(Calendar.YEAR)-2000
        val yyHex = nnToHex(year)
        val month:Int = cal.get(Calendar.MONTH)+1
        val mmHex = nnToHex(month)
        val day:Int = cal.get(Calendar.DAY_OF_MONTH)
        val ddHex = nnToHex(day)
        ret[0]=yyHex //year yy
        ret[1]=mmHex//month mm
        ret[2]=ddHex  //day dd
        return ret
    }


}

class Pdol (val cardPdolBody: ByteArray, val pdolType: Int, val cardType: CardType /*=CardType.VISA*/ ) {

    val cardPdol: MutableList<CardPdolItem> = buildCardPdol()
    private var termPdolLen = 0
    val termPdol: ByteArray = buildTermPdol()
    companion object {
        const val PDOL_TYPE_PDOL = 0
        const val PDOL_TYPE_CDOL1 = 1
    }

    private fun buildCardPdol(): MutableList<CardPdolItem> {
        Log.d(LOG_PDOL_TAG,"buildCardPdol() started")
        val ret= mutableListOf<CardPdolItem>()
        var i=0
        while (i<cardPdolBody.size) {
            val codeLength = EmvTag.getMyTagCodeLength(cardPdolBody,i)
            var code  = cardPdolBody[i].toUByte().toInt()
            if (codeLength>1)
                    code = code * 256 + cardPdolBody[i+1].toUByte().toInt()
            if( codeLength>2) // should never happen
                code = code * 256 + cardPdolBody[i+2].toUByte().toInt()
            i += codeLength
            val len = cardPdolBody[i].toInt()
            termPdolLen += len
            val cardPdolItem = CardPdolItem(code, len, cardType)
            ret.add(cardPdolItem)
            Log.d(LOG_PDOL_TAG, "PDOL Item added, code=${code.toString(16)}. len=$len")
            i++
        }
        termPdolLen = termPdolLen.coerceAtMost(127)  // just in case. Never should happen
        Log.d(LOG_PDOL_TAG,"buildCardPdol() ended. List size = ${ret.size}. \nlist: $ret")
        return ret
    }

    private fun buildTermPdol() : ByteArray {
        Log.d(LOG_PDOL_TAG,"buildTermPdol() started, termPdolLen = $termPdolLen")
        val result: ByteArray
        var dolDataIndex: Int
        when (pdolType) {
            PDOL_TYPE_PDOL -> {
                result = ByteArray(termPdolLen+2) // +2 bytes for tag code and length
                result[0] = 0x83.toByte() // pdol tag
                result[1] = termPdolLen.toByte()  // pdol tag length.
                dolDataIndex = 2 // where data starts
            }
            else -> { //CDOL1
                result = ByteArray(termPdolLen) // no tag code and length
                dolDataIndex = 0 // where data starts
            }
        }

        for (item in cardPdol) {
            for (i in 0 until item.cardPdolIemLen) {
                result[dolDataIndex+i] = item.termPdolItem[i]
            }
            dolDataIndex += item.cardPdolIemLen
        }
        Log.d(LOG_PDOL_TAG,"buildTermPdol() ended, resulted PDOL has ${result.size} Bytes. pdol=${CardPdolItem.toHex(result)}")
        return result
    }
 }