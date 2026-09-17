package com.dameokja.backend.user.application;

public record ProfileImageChange(Action action, String imageKey) {
    public enum Action { KEEP, REPLACE, DELETE }
    public static ProfileImageChange keep() { return new ProfileImageChange(Action.KEEP, null); }
    public static ProfileImageChange replace(String key) { return new ProfileImageChange(Action.REPLACE, key); }
    public static ProfileImageChange delete() { return new ProfileImageChange(Action.DELETE, null); }
}
