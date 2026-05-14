package com.simpligility.maven.multimodule;

import com.google.common.collect.ImmutableList;

public class App {
    public static void main(String[] args) {
        ImmutableList<String> names = ImmutableList.of("world", "chainguard");
        names.forEach(name -> System.out.println(StringUtils.capitalize(name)));
    }
}
