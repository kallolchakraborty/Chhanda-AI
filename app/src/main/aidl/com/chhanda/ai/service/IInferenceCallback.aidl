package com.chhanda.ai.service;

oneway interface IInferenceCallback {
    void onToken(String text, double tps);
    void onError(String error);
    void onComplete();
}
