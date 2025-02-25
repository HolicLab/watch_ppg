package com.samsung.health.hrtracker;

// PpgDataWrapper.java (session_id와 datas 배열을 포함하는 객체)
import java.util.List;
public class PpgDataWrapper {
    private String session_id;
    private List<PpgDataItem> datas;

    public PpgDataWrapper(String session_id, List<PpgDataItem> datas) {
        this.session_id = session_id;
        this.datas = datas;
    }

    // Getters and setters
    public String getSession_id() { return session_id; }
    public void setSession_id(String session_id) { this.session_id = session_id; }
    public List<PpgDataItem> getDatas() { return datas; }
    public void setDatas(List<PpgDataItem> datas) { this.datas = datas; }
}
