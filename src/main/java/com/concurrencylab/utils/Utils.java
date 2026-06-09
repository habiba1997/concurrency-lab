package com.concurrencylab.utils;

import java.util.Random;

public class Utils {

    public static Integer getId(){
        Random random = new Random();
        return random.nextInt();
    }
}
