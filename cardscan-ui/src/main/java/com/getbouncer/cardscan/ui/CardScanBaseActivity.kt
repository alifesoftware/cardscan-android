package com.getbouncer.cardscan.ui

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.getbouncer.cardscan.ui.result.MainLoopAggregator
import com.getbouncer.cardscan.ui.result.MainLoopState
import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.AnalyzerLoopErrorListener
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.time.Clock
import com.getbouncer.scan.framework.time.seconds
import com.getbouncer.scan.payment.card.formatPan
import com.getbouncer.scan.payment.card.getCardIssuer
import com.getbouncer.scan.payment.card.isValidPan
import com.getbouncer.scan.payment.ml.SSDOcr
import com.getbouncer.scan.payment.ml.ssd.DetectionBox
import com.getbouncer.scan.payment.ml.ssd.calculateCardFinderCoordinatesFromObjectDetection
import com.getbouncer.scan.ui.DebugDetectionBox
import com.getbouncer.scan.ui.ScanFlow
import com.getbouncer.scan.ui.ScanResultListener
import com.getbouncer.scan.ui.SimpleScanActivity
import com.getbouncer.scan.ui.util.fadeIn
import com.getbouncer.scan.ui.util.fadeOut
import com.getbouncer.scan.ui.util.getColorByRes
import com.getbouncer.scan.ui.util.setTextSizeByRes
import com.getbouncer.scan.ui.util.setVisible
import com.getbouncer.scan.ui.util.show
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

interface CardScanResultListener : ScanResultListener {

    /**
     * A payment card was successfully scanned.
     */
    fun cardScanned(scanResult: CardScanActivityResult)

    /**
     * The user requested to enter payment card details manually.
     */
    fun enterManually()
}

@Parcelize
data class CardScanActivityResult(
    val pan: String?,
    val expiryDay: String?,
    val expiryMonth: String?,
    val expiryYear: String?,
    val networkName: String?,
    val cvc: String?,
    val cardholderName: String?,
    val errorString: String?,
) : Parcelable

private val MINIMUM_RESOLUTION = Size(1280, 720) // minimum size of an object square

private fun DetectionBox.forDebugPan() = DebugDetectionBox(rect, confidence, label.toString())
private fun DetectionBox.forDebugObjDetect(cardFinder: Rect, previewImage: Size) = DebugDetectionBox(
    calculateCardFinderCoordinatesFromObjectDetection(rect, previewImage, cardFinder),
    confidence,
    label.toString(),
)

abstract class CardScanBaseActivity :
    SimpleScanActivity(),
    AggregateResultListener<MainLoopAggregator.InterimResult, MainLoopAggregator.FinalResult>,
    AnalyzerLoopErrorListener {

    /**
     * The text view that lets a user manually enter a card.
     */
    protected open val enterCardManuallyTextView: TextView by lazy { TextView(this) }

    protected abstract val enableEnterCardManually: Boolean
    protected abstract val enableNameExtraction: Boolean
    protected abstract val enableExpiryExtraction: Boolean

    /**
     * The listener which handles results from the scan.
     */
    abstract override val resultListener: CardScanResultListener

    private var mainLoopIsProducingResults = AtomicBoolean(false)
    private val hasPreviousValidResult = AtomicBoolean(false)
    private var lastDebugFrameUpdate = Clock.markNow()

    override val scanFlow: ScanFlow by lazy {
        CardScanFlow(enableNameExtraction, enableExpiryExtraction, this, this)
    }

    override val minimumAnalysisResolution: Size = MINIMUM_RESOLUTION

    /**
     * During on create
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterCardManuallyTextView.setOnClickListener { enterCardManually() }
    }

    override fun addUiComponents() {
        super.addUiComponents()
        appendUiComponents(enterCardManuallyTextView)
    }

    override fun setupUiComponents() {
        super.setupUiComponents()

        enterCardManuallyTextView.text = getString(R.string.bouncer_enter_card_manually)
        enterCardManuallyTextView.setTextSizeByRes(R.dimen.bouncerEnterCardManuallyTextSize)
        enterCardManuallyTextView.gravity = Gravity.CENTER

        enterCardManuallyTextView.setVisible(enableEnterCardManually)

        if (isBackgroundDark()) {
            enterCardManuallyTextView.setTextColor(getColorByRes(R.color.bouncerEnterCardManuallyColorDark))
        } else {
            enterCardManuallyTextView.setTextColor(getColorByRes(R.color.bouncerEnterCardManuallyColorLight))
        }
    }

    override fun setupUiConstraints() {
        super.setupUiConstraints()

        enterCardManuallyTextView.layoutParams = ConstraintLayout.LayoutParams(
            0, // width
            ConstraintLayout.LayoutParams.WRAP_CONTENT, // height
        ).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
            marginEnd = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
            bottomMargin = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
            topMargin = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
        }

        enterCardManuallyTextView.addConstraints {
            connect(it.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(it.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(it.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        }
    }

    /**
     * Cancel scanning to enter a card manually
     */
    protected open fun enterCardManually() {
        runBlocking { scanStat.trackResult("enter_card_manually") }
        resultListener.enterManually()
        closeScanner()
    }

    /**
     * Card was successfully scanned, return an activity result.
     */
    protected open fun cardScanned(result: CardScanActivityResult) {
        runBlocking { scanStat.trackResult("card_scanned") }
        resultListener.cardScanned(result)
        closeScanner()
    }

    /**
     * Display the card pan. If debug, show the instant pan. if not, show the most likely pan.
     */
    private fun displayPan(instantPan: String?, mostLikelyPan: String?) {
        if (Config.isDebug && instantPan != null) {
            cardNumberTextView.text = formatPan(instantPan)
            cardNumberTextView.show()
        } else if (!mostLikelyPan.isNullOrEmpty() && isValidPan(mostLikelyPan)) {
            cardNumberTextView.text = formatPan(mostLikelyPan)
            cardNumberTextView.fadeIn()
        }
    }

    /**
     * Display the cardholder name. If debug, show the instant name. if not, show the most likely name.
     */
    private fun displayName(instantName: String?, mostLikelyName: String?) {
        if (Config.isDebug && instantName != null) {
            cardNameTextView.text = instantName
            cardNameTextView.show()
        } else if (!mostLikelyName.isNullOrEmpty()) {
            cardNameTextView.text = mostLikelyName
            cardNameTextView.fadeIn()
        }
    }

    /**
     * A final result was received from the aggregator. Set the result from this activity.
     */
    override suspend fun onResult(result: MainLoopAggregator.FinalResult) = launch(Dispatchers.Main) {
        // Only show the expiry dates that are not expired
        val (expiryMonth, expiryYear) = if (result.expiry?.isValidExpiry() == true) {
            (result.expiry.month to result.expiry.year)
        } else {
            (null to null)
        }

        cardScanned(
            CardScanActivityResult(
                pan = result.pan,
                networkName = getCardIssuer(result.pan).displayName,
                expiryDay = null,
                expiryMonth = expiryMonth,
                expiryYear = expiryYear,
                cvc = null,
                cardholderName = result.name,
                errorString = result.errorString,
            )
        )
    }.let { Unit }

    /**
     * An interim result was received from the result aggregator.
     */
    override suspend fun onInterimResult(result: MainLoopAggregator.InterimResult) = launch(Dispatchers.Main) {
        if (!mainLoopIsProducingResults.getAndSet(true)) {
            scanStat.trackResult("first_image_processed")
        }

        if (result.state is MainLoopState.OcrRunning && !hasPreviousValidResult.getAndSet(true)) {
            scanStat.trackResult("ocr_pan_observed")
            enterCardManuallyTextView.fadeOut()
        }

        val willRunNameAndExpiry = (result.analyzerResult.isExpiryExtractionAvailable && enableExpiryExtraction) ||
            (result.analyzerResult.isNameExtractionAvailable && enableNameExtraction)

        when (result.state) {
            is MainLoopState.Initial -> changeScanState(ScanState.NotFound)
            is MainLoopState.OcrRunning -> {
                displayPan(result.analyzerResult.pan, result.state.getMostLikelyPan())
                if (willRunNameAndExpiry) {
                    changeScanState(ScanState.FoundLong)
                } else {
                    changeScanState(ScanState.FoundShort)
                }
            }
            is MainLoopState.NameAndExpiryRunning -> {
                displayName(result.analyzerResult.pan, result.state.getMostLikelyName())
                if (willRunNameAndExpiry) {
                    changeScanState(ScanState.FoundLong)
                } else {
                    changeScanState(ScanState.FoundShort)
                }
            }
            is MainLoopState.Finished -> changeScanState(ScanState.Correct)
        }

        showDebugFrame(result.frame, result.analyzerResult.panDetectionBoxes, result.analyzerResult.objDetectionBoxes)
    }.let { Unit }

    override suspend fun onReset() = launch(Dispatchers.Main) { changeScanState(ScanState.NotFound) }.let { Unit }

    private suspend fun showDebugFrame(
        frame: SSDOcr.Input,
        panBoxes: List<DetectionBox>?,
        objectBoxes: List<DetectionBox>?,
    ) {
        if (Config.isDebug && lastDebugFrameUpdate.elapsedSince() > 1.seconds) {
            lastDebugFrameUpdate = Clock.markNow()
            val bitmap = withContext(Dispatchers.Default) { SSDOcr.cropImage(frame) }
            debugImageView.setImageBitmap(bitmap)
            if (panBoxes != null) {
                debugOverlayView.setBoxes(panBoxes.map { it.forDebugPan() })
            }
            if (objectBoxes != null) {
                debugOverlayView.setBoxes(objectBoxes.map { it.forDebugObjDetect(frame.cardFinder, frame.previewSize) })
            }

            Log.d(Config.logTag, "Delay between capture and result for this frame was ${frame.capturedAt.elapsedSince()}")
        }
    }

    override fun onAnalyzerFailure(t: Throwable): Boolean {
        analyzerFailureCancelScan(t)
        return true
    }

    override fun onResultFailure(t: Throwable): Boolean {
        analyzerFailureCancelScan(t)
        return true
    }
}
