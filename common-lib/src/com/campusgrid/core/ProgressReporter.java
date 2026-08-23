package com.campusgrid.core;

import java.io.Serializable;

public interface ProgressReporter extends Serializable {
    void reportProgress(double percentageComplete, String statusMessage);
}