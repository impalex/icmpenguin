/*
 * Copyright (c) 2025 Alexander Yaburov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#ifndef ICMPENGUIN_JNI_METHODS_H
#define ICMPENGUIN_JNI_METHODS_H

#include <jni.h>

struct JNIMethodInfo {
    jmethodID mid;
    jclass cls;
    const char *class_name;
    const char *method_name;
    const char *method_sig;
};

JNIMethodInfo JNI_methods[] = {
        {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeManager",
                .method_name = "probeCallback",
                .method_sig = "(ILme/impa/icmpenguin/ProbeResult;)V"
        }, {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeResult$Success",
                .method_name = "<init>",
                .method_sig = "(ILjava/lang/String;IIII[B)V"
        }, {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeResult$Timeout",
                .method_name = "<init>",
                .method_sig = "(ILjava/lang/String;II)V"
        }, {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeResult$ConnectionRefused",
                .method_name = "<init>",
                .method_sig = "(ILjava/lang/String;IILjava/lang/String;I)V"
        }, {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeResult$HostUnreachable",
                .method_name = "<init>",
                .method_sig = "(ILjava/lang/String;IILjava/lang/String;I)V"
        }, {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeResult$NetUnreachable",
                .method_name = "<init>",
                .method_sig = "(ILjava/lang/String;IILjava/lang/String;I)V"
        }, {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeResult$NetError",
                .method_name = "<init>",
                .method_sig = "(ILjava/lang/String;IILjava/lang/String;IIII)V"
        }, {
                .mid = nullptr,
                .cls = nullptr,
                .class_name = "me/impa/icmpenguin/ProbeResult$Unknown",
                .method_name = "<init>",
                .method_sig = "(ILjava/lang/String;IILjava/lang/String;)V"
        }
};

#define JNI_METHOD_COUNT (sizeof(JNI_methods) / sizeof(JNIMethodInfo))
#define JNI_METHOD(idx) JNI_methods[idx]
#define JNI_METHOD_MID(idx) JNI_METHOD(idx).mid
#define JNI_METHOD_CLS(idx) JNI_METHOD(idx).cls
#define CALLBACK_MID JNI_METHOD_MID(0)
#define CALLBACK_CLS JNI_METHOD_CLS(0)
#define RESULT_SUCCESS_MID JNI_METHOD_MID(1)
#define RESULT_SUCCESS_CLS JNI_METHOD_CLS(1)
#define RESULT_TIMEOUT_MID JNI_METHOD_MID(2)
#define RESULT_TIMEOUT_CLS JNI_METHOD_CLS(2)
#define RESULT_CONNECTION_REFUSED_MID JNI_METHOD_MID(3)
#define RESULT_CONNECTION_REFUSED_CLS JNI_METHOD_CLS(3)
#define RESULT_HOST_UNREACHABLE_MID JNI_METHOD_MID(4)
#define RESULT_HOST_UNREACHABLE_CLS JNI_METHOD_CLS(4)
#define RESULT_NET_UNREACHABLE_MID JNI_METHOD_MID(5)
#define RESULT_NET_UNREACHABLE_CLS JNI_METHOD_CLS(5)
#define RESULT_NET_ERROR_MID JNI_METHOD_MID(6)
#define RESULT_NET_ERROR_CLS JNI_METHOD_CLS(6)
#define RESULT_UNKNOWN_MID JNI_METHOD_MID(7)
#define RESULT_UNKNOWN_CLS JNI_METHOD_CLS(7)


#endif //ICMPENGUIN_JNI_METHODS_H
