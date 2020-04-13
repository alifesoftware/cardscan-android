
package com.getbouncer.cardscan.base;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.getbouncer.cardscan.base.ssd.DetectedOcrBox;
import com.getbouncer.cardscan.base.ssd.DetectionBox;
import com.getbouncer.cardscan.base.ssd.OcrPriorsGenerator;
import com.getbouncer.cardscan.base.ssd.SSD;
import com.getbouncer.cardscan.base.ssd.Size;
import com.getbouncer.cardscan.base.ssd.domain.ClassifierScores;
import com.getbouncer.cardscan.base.ssd.domain.SizeAndCenter;
import com.getbouncer.cardscan.base.util.ArrayExtensions;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import java.util.ArrayList;
import java.util.Map;

import kotlin.jvm.functions.Function1;


public class SSDOcrDetect {
    @Nullable private static SSDOcrModel ssdOcrModel = null;
    @Nullable private static float[][] priors = null;

    /** to store YMin values of all boxes */
    private final List<Float> yMinArray = new ArrayList<Float>();

    /** to store the YMax values of all the boxes */
    private final List<Float> yMaxArray = new ArrayList<Float>();


    /** to store the width values of all the boxes */
    private final List<Float> widthArray = new ArrayList<Float>();


    public final List<DetectedOcrBox> objectBoxes = new ArrayList<>();
    public boolean hadUnrecoverableException = false;

    /** We don't use the following two for now */
    public static boolean USE_GPU = false;

    public SSDOcrDetect() {
    }

    static boolean isInit() {
        return ssdOcrModel != null;
    }

    @Nullable
    private String processQuickRead(List<DetectionBox> detectionBoxes,
                                    Bitmap image, Boolean strict){

        String numberOCR;
        StringBuilder num = new StringBuilder();

       Collections.sort(detectionBoxes, new Comparator<DetectionBox>() {
           @Override
           public int compare(DetectionBox o1, DetectionBox o2) {
               return Float.compare((o1.getRect().top / 2 + (o1.getRect().bottom / 2)),
                       o2.getRect().top / 2 + (o2.getRect().bottom / 2));
           }
       });

        List<DetectionBox> firstGroup = detectionBoxes.subList(0, 4);
        List<DetectionBox> secondGroup = detectionBoxes.subList(4, 8);
        List<DetectionBox> thirdGroup = detectionBoxes.subList(8, 12);
        List<DetectionBox> fourthGroup = detectionBoxes.subList(12, 16);

        Collections.sort(firstGroup, new Comparator<DetectionBox>() {
            @Override
            public int compare(DetectionBox o1, DetectionBox o2) {
                return Float.compare(o1.getRect().left, o2.getRect().left);
            }
        });

        for (DetectionBox box : firstGroup) {
            num.append(box.getLabel());
        }

        Collections.sort(secondGroup, new Comparator<DetectionBox>() {
            @Override
            public int compare(DetectionBox o1, DetectionBox o2) {
                return Float.compare(o1.getRect().left, o2.getRect().left);
            }
        });

        for (DetectionBox box : secondGroup) {
            num.append(box.getLabel());
        }

        Collections.sort(thirdGroup, new Comparator<DetectionBox>() {
            @Override
            public int compare(DetectionBox o1, DetectionBox o2) {
                return Float.compare(o1.getRect().left, o2.getRect().left);
            }
        });

        for (DetectionBox box : thirdGroup) {
            num.append(box.getLabel());
        }

        Collections.sort(fourthGroup, new Comparator<DetectionBox>() {
            @Override
            public int compare(DetectionBox o1, DetectionBox o2) {
                return Float.compare(o1.getRect().left, o2.getRect().left);
            }
        });

        for (DetectionBox box : fourthGroup) {
            num.append(box.getLabel());
        }



        if (CreditCardUtils.isValidCardNumber(num.toString())){
            numberOCR = num.toString();
            Log.e("OCR Number passed", numberOCR);
        } else {
            Log.e("OCR Number failed", num.toString());
            if (strict) {
                numberOCR = null;
            } else {
                numberOCR = num.toString();
            }
        }

        return numberOCR;

    }

    private Boolean isQuickRead(List<DetectionBox> detectionBoxes, Bitmap image){

        if (detectionBoxes.isEmpty() || detectionBoxes.size() != 16){
            return false;
        }


        final List<Float> boxCenters = new ArrayList<Float>();
        final List<Float> boxHeights = new ArrayList<Float>();
        float aggregateDeviation = 0;

        for (DetectionBox detectionBox : detectionBoxes) {

            boxCenters.add((detectionBox.getRect().top * image.getHeight()
                            + detectionBox.getRect().bottom * image.getHeight())/2);

            boxHeights.add(Math.abs(detectionBox.getRect().top * image.getWidth()
                    - detectionBox.getRect().bottom * image.getWidth()) );
        }

        Collections.sort(boxCenters);
        float medianYCenter = boxCenters.get(boxCenters.size() / 2);
        Collections.sort(boxHeights);
        float medianHeight = boxHeights.get(boxHeights.size() / 2);

        for (float center : boxCenters){
            aggregateDeviation += Math.abs(medianYCenter - center);
        }

        if (aggregateDeviation > 2.0 * medianHeight)
        {

            return true;
        }


        return false;
    }

    @Nullable
    private String ssdOutputToPredictions(@NonNull Bitmap image, boolean strict) {
        if (ssdOcrModel == null || priors == null) {
            return null;
        }

        String numberOCR;
        float[][] k_boxes = SSD.rearrangeOCRArray(ssdOcrModel.outputLocations, SSDOcrModel.FEATURE_MAP_SIZES,
                SSDOcrModel.NUM_OF_PRIORS_PER_ACTIVATION, SSDOcrModel.NUM_OF_COORDINATES);
        k_boxes = ArrayExtensions.reshape(k_boxes, SSDOcrModel.NUM_OF_COORDINATES);
        SizeAndCenter.adjustLocations(k_boxes, priors, SSDOcrModel.CENTER_VARIANCE, SSDOcrModel.SIZE_VARIANCE);
        SizeAndCenter.toRectForm(k_boxes);

        float[][] k_scores = SSD.rearrangeOCRArray(ssdOcrModel.outputClasses, SSDOcrModel.FEATURE_MAP_SIZES,
                SSDOcrModel.NUM_OF_PRIORS_PER_ACTIVATION, SSDOcrModel.NUM_OF_CLASSES);
        k_scores = ArrayExtensions.reshape(k_scores, SSDOcrModel.NUM_OF_CLASSES);
        ClassifierScores.softMax2D(k_scores);

        List<DetectionBox> detectionBoxes = SSD.extractPredictions(
            k_scores,
            k_boxes,
            new Size(image.getWidth(), image.getHeight()),
            SSDOcrModel.PROB_THRESHOLD,
            SSDOcrModel.IOU_THRESHOLD,
            SSDOcrModel.TOP_K,
            new Function1<Integer, Integer>() {
                @Override
                public Integer invoke(Integer o) {
                    if (o == 10) {
                        return 0;
                    } else {
                        return o;
                    }
                }
            }
        );

        if (isQuickRead(detectionBoxes, image)){
            Log.e("Quick Read it is ", "Error");

            numberOCR = processQuickRead(detectionBoxes, image, strict);
            return numberOCR;

        }

        Collections.sort(detectionBoxes, new Comparator<DetectionBox>() {
            @Override
            public int compare(DetectionBox o1, DetectionBox o2) {
                return Float.compare(o1.getRect().left, o2.getRect().left);
            }
        });

        for (DetectionBox detectionBox : detectionBoxes) {
            objectBoxes.add(new DetectedOcrBox(
                    detectionBox.getRect().left,
                    detectionBox.getRect().top,
                    detectionBox.getRect().right,
                    detectionBox.getRect().bottom,
                    detectionBox.getConfidence(),
                    detectionBox.getImageSize().getWidth(),
                    detectionBox.getImageSize().getHeight(),
                    detectionBox.getLabel()
            ));

            // add the YMin value of the current box
            yMinArray.add(detectionBox.getRect().top * image.getHeight());
            // add the YMax value of the current box
            yMaxArray.add(detectionBox.getRect().bottom * image.getHeight());

            // add the YMax value of the current box
            widthArray.add(Math.abs(detectionBox.getRect().right * image.getWidth()
                                    - detectionBox.getRect().left * image.getWidth()) );
        }

        Collections.sort(yMinArray);
        Collections.sort(yMaxArray);
        Collections.sort(widthArray);
        float medianYMin = 0;
        float medianYMax = 0;
        float medianHeight = 0;
        float medianYCenter = 0;
        float medianWidth = 0;

        if (!yMaxArray.isEmpty() && !yMinArray.isEmpty()) {
            medianYMin = yMinArray.get(yMinArray.size() / 2);
            medianYMax = yMaxArray.get(yMaxArray.size() / 2);
            medianWidth = widthArray.get(widthArray.size() / 2);
            medianHeight = Math.abs(medianYMax - medianYMin);
            medianYCenter = (medianYMax + medianYMin) / 2;
        }

        StringBuilder num = new StringBuilder();
        for (DetectedOcrBox box : objectBoxes) {
            if (Math.abs(box.rect.centerY() - medianYCenter) <= medianHeight
                    && box.rect.height() <= SSDOcrModel.TOLERANCE * medianHeight
                    && box.rect.width() <= SSDOcrModel.TOLERANCE *  medianWidth) {
                num.append(box.label);
            }
        }
        if (CreditCardUtils.isValidCardNumber(num.toString())){
            numberOCR = num.toString();
            Log.e("OCR Number passed", numberOCR);
        } else {
            Log.e("OCR Number failed", num.toString());
            if (strict) {
                numberOCR = null;
            } else {
                numberOCR = num.toString();
            }
        }

        return numberOCR;
    }

    /**
     * Run SSD Model and use the prediction API to post process
     * the model output
     */
    private String runModel(Bitmap image) {
        return runModel(image, true);
    }

    /**
     * Run SSD Model and use the prediction API to post process
     * the model output
     */
    @Nullable
    private String runModel(@NonNull Bitmap image, boolean strict) {
        final long startTime = SystemClock.uptimeMillis();
        if (ssdOcrModel == null) {
            return "";
        }

        ssdOcrModel.classifyFrame(image);
        Log.d("Before SSD Post Process", String.valueOf(SystemClock.uptimeMillis() - startTime));
        String number = ssdOutputToPredictions(image, strict);
        Log.d("After SSD Post Process", String.valueOf(SystemClock.uptimeMillis() - startTime));

        return number;
    }

    public interface OnDetectListener {
        void complete(String result);
    }

    private static <T, V extends Comparable<V>> T getKeyByMaxValue(Map<T, V> map) {
        Map.Entry<T, V> maxEntry = null;
        for (Map.Entry<T, V> entry : map.entrySet()) {
            if (maxEntry == null || entry.getValue().compareTo(maxEntry.getValue()) > 0) {
                maxEntry = entry;
            }
        }

        if (maxEntry != null) {
            return maxEntry.getKey();
        } else {
            return null;
        }
    }

    public static void runInCompletionLoop(
        @NonNull final List<Bitmap> frames,
        @NonNull final Context context,
        @Nullable final OnDetectListener detection
    ) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Map<String, Integer> results = new HashMap<>();
                for (Bitmap ocrFrame: frames) {
                    if (ocrFrame != null) {
                        SSDOcrDetect ocrDetect = new SSDOcrDetect();
                        String prediction = ocrDetect.predict(ocrFrame, context);

                        if (prediction != null) {
                            Integer count = results.get(prediction);
                            if (count != null) {
                                results.put(prediction, count + 1);
                            } else {
                                results.put(prediction, 1);
                            }
                        }
                    }
                }

                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (detection != null) {
                            detection.complete(getKeyByMaxValue(results));
                        }
                    }
                });
            }
        }).start();
    }

    @Nullable
    public synchronized String predict(@NonNull Bitmap image, @NonNull Context context) {
        final int NUM_THREADS = 4;
        try {
            boolean createdNewModel = false;

            try{
                if (ssdOcrModel == null){
                    ssdOcrModel = new SSDOcrModel(context);
                    ssdOcrModel.setNumThreads(NUM_THREADS);
                    /** Since all the frames use the same set of priors
                     * We generate these once and use for all the frame
                     */
                    if ( priors == null){
                        priors = OcrPriorsGenerator.combinePriors();
                    }

                }
            } catch (Error | Exception e){
                Log.e("SSD", "Couldn't load ssd", e);
            }


            try {
                return runModel(image);
            } catch (Error | Exception e) {
                Log.i("OCR", "runModel exception, retry object detection", e);
                ssdOcrModel = new SSDOcrModel(context);
                return runModel(image);
            }
        } catch (Error | Exception e) {
            Log.e("OCR", "unrecoverable exception on ObjectDetect", e);
            hadUnrecoverableException = true;
            return null;
        }
    }

    @Nullable
    public synchronized String predict(@NonNull Bitmap image, @NonNull Context context, boolean strict) {
        final int NUM_THREADS = 4;
        try {
            boolean createdNewModel = false;

            try{
                if (ssdOcrModel == null){
                    ssdOcrModel = new SSDOcrModel(context);
                    ssdOcrModel.setNumThreads(NUM_THREADS);
                    /* Since all the frames use the same set of priors
                     * We generate these once and use for all the frame
                     */
                    if ( priors == null){
                        priors = OcrPriorsGenerator.combinePriors();
                    }

                }
            } catch (Error | Exception e){
                Log.e("SSD", "Couldn't load ssd", e);
            }


            try {
                return runModel(image, strict);
            } catch (Error | Exception e) {
                Log.i("ObjectDetect", "runModel exception, retry object detection", e);
                ssdOcrModel = new SSDOcrModel(context);
                return runModel(image, strict);
            }
        } catch (Error | Exception e) {
            Log.e("ObjectDetect", "unrecoverable exception on ObjectDetect", e);
            hadUnrecoverableException = true;
            return null;
        }
    }

}