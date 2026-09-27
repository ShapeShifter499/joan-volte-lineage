package com.android.tools.r8.keepanno.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface UsedByNative { String description() default ""; KeepItemKind kind() default KeepItemKind.DEFAULT; }
