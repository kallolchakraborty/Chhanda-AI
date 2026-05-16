package com.chhanda.ai.service;

import com.chhanda.ai.service.IInferenceCallback;

interface IInferenceService {
    void initModel(String path);
    
    void generateResponse(
        String prompt, 
        in List<String> historyRoles, 
        in List<String> historyTexts, 
        String systemInstruction,
        in List<String> attachmentUris,
        IInferenceCallback callback
    );
    
    void resetSession();
    boolean isSessionActive();
    void stopInference();
    boolean isModelLoaded();
    boolean isModelLoading();
    String getCurrentModelName();
    void closeEngine();
    
    float getLoadingProgress();
    double getPerformanceMetrics();
    boolean isMultimodal();
}
