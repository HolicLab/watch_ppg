package com.samsung.health.hrtracker;

public class PpgDataItem {
    private float ppg_value;
    private float focus_score;
    private String time;

    public PpgDataItem(float ppg_value, float focus_score, String time) {
        this.ppg_value = ppg_value;
        this.focus_score = focus_score;
        this.time = time;
    }
    public float getPpg_value() { return ppg_value; }
    public void setPpg_value(float ppg_value) { this.ppg_value = ppg_value; }
    public float getFocus_score() { return focus_score; }
    public void setFocus_score(int focus_score) { this.focus_score = focus_score; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
}
