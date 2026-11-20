package com.lookmate.app.matching;

/** Where the worker gets image bytes: the private photo bucket by object key. Tests replace it. */
interface ImageSource {

    byte[] read(String objectKey);
}
