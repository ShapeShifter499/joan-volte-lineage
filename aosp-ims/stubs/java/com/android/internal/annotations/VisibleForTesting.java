package com.android.internal.annotations;
public @interface VisibleForTesting { enum Visibility { PROTECTED, PACKAGE, PRIVATE } Visibility visibility() default Visibility.PRIVATE; }
