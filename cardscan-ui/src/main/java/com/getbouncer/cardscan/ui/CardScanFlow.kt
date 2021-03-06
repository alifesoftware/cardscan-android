package com.getbouncer.cardscan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import androidx.lifecycle.LifecycleOwner
import com.getbouncer.cardscan.ui.analyzer.PaymentCardOcrAnalyzer
import com.getbouncer.cardscan.ui.result.MainLoopAggregator
import com.getbouncer.cardscan.ui.result.MainLoopState
import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.AnalyzerLoopErrorListener
import com.getbouncer.scan.framework.AnalyzerPoolFactory
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.time.Clock
import com.getbouncer.scan.framework.util.cacheFirstResultSuspend
import com.getbouncer.scan.payment.analyzer.NameAndExpiryAnalyzer
import com.getbouncer.scan.payment.ml.AlphabetDetect
import com.getbouncer.scan.payment.ml.ExpiryDetect
import com.getbouncer.scan.payment.ml.SSDOcr
import com.getbouncer.scan.payment.ml.TextDetect
import com.getbouncer.scan.ui.ScanFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * This class contains the scanning logic required for analyzing a credit card for scanning purposes.
 */
class CardScanFlow(
    private val enableNameExtraction: Boolean,
    private val enableExpiryExtraction: Boolean,
    private val resultListener: AggregateResultListener<MainLoopAggregator.InterimResult, MainLoopAggregator.FinalResult>,
    private val errorListener: AnalyzerLoopErrorListener
) : ScanFlow {
    companion object {

        /**
         * This field represents whether the flow was initialized with name and expiry enabled.
         */
        var attemptedNameAndExpiryInitialization = false
            private set

        private val getTextDetectorModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            TextDetect.ModelFetcher(context).fetchData(forImmediateUse)
        }
        private val getAlphabetDetectorModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            AlphabetDetect.ModelFetcher(context).fetchData(forImmediateUse)
        }
        private val getExpiryDetectorModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            ExpiryDetect.ModelFetcher(context).fetchData(forImmediateUse)
        }
        private val getSsdOcrModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            SSDOcr.ModelFetcher(context).fetchData(forImmediateUse)
        }

        /**
         * Warm up the analyzers for card scanner. This method is optional, but will increase the speed at which the
         * scan occurs.
         *
         * @param context: A context to use for warming up the analyzers.
         */
        @JvmStatic
        fun warmUp(context: Context, apiKey: String, initializeNameAndExpiryExtraction: Boolean) {
            Config.apiKey = apiKey

            // pre-fetch all of the models used by this flow.
            GlobalScope.launch(Dispatchers.Default) {
                if (initializeNameAndExpiryExtraction) {
                    attemptedNameAndExpiryInitialization = true
                    getTextDetectorModel(context, false)
                    getAlphabetDetectorModel(context, false)
                    getExpiryDetectorModel(context, false)
                }
                getSsdOcrModel(context, false)
            }
        }
    }

    /**
     * If this is true, do not start the flow.
     */
    private var canceled = false

    private lateinit var mainLoopResultAggregator: MainLoopAggregator
    private var mainLoopJob: Job? = null

    /**
     * Start the image processing flow for scanning a card.
     *
     * @param context: The context used to download analyzers if needed
     * @param imageStream: The flow of images to process
     * @param previewSize: The size of the preview frame where the view finder is located
     */
    override fun startFlow(
        context: Context,
        imageStream: Flow<Bitmap>,
        previewSize: Size,
        viewFinder: Rect,
        lifecycleOwner: LifecycleOwner,
        coroutineScope: CoroutineScope
    ) {
        if (canceled) {
            return
        }

        mainLoopResultAggregator = MainLoopAggregator(
            listener = resultListener,
            enableNameExtraction = enableNameExtraction,
            enableExpiryExtraction = enableExpiryExtraction
        )

        val analyzerPool = runBlocking {
            val nameDetect = if (attemptedNameAndExpiryInitialization) {
                NameAndExpiryAnalyzer.Factory<MainLoopState>(
                    TextDetect.Factory(context, getTextDetectorModel(context, true)),
                    AlphabetDetect.Factory(context, getAlphabetDetectorModel(context, true)),
                    ExpiryDetect.Factory(context, getExpiryDetectorModel(context, true))
                )
            } else {
                null
            }

            AnalyzerPoolFactory(
                PaymentCardOcrAnalyzer.Factory(SSDOcr.Factory(context, getSsdOcrModel(context, true)), nameDetect)
            ).buildAnalyzerPool()
        }

        // make this result aggregator pause and reset when the lifecycle pauses.
        mainLoopResultAggregator.bindToLifecycle(lifecycleOwner)

        val mainLoop = ProcessBoundAnalyzerLoop(
            analyzerPool = analyzerPool,
            resultHandler = mainLoopResultAggregator,
            analyzerLoopErrorListener = errorListener
        )

        mainLoop.subscribeTo(
            flow = imageStream.map {
                SSDOcr.Input(
                    fullImage = it,
                    previewSize = previewSize,
                    cardFinder = viewFinder,
                    capturedAt = Clock.markNow()
                )
            },
            processingCoroutineScope = coroutineScope
        )
    }

    /**
     * In the event that the scan cannot complete, halt the flow to halt analyzers and free up CPU and memory.
     */
    override fun cancelFlow() {
        canceled = true
        if (::mainLoopResultAggregator.isInitialized) {
            mainLoopResultAggregator.cancel()
        }

        mainLoopJob?.apply { if (isActive) { cancel() } }
    }
}
