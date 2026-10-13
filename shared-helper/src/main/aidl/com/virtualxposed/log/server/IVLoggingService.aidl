package com.virtualxposed.log.server;

import com.virtualxposed.log.client.LogMessage;

interface IVLoggingService {
    void log(in LogMessage message);

    int getFingerprintLogAction();
    boolean setFingerprintLogAction(int action);

    const int LOG_ONLY = 0;
    const int TOAST = 1;
    const int KILL_APP = 2;
}