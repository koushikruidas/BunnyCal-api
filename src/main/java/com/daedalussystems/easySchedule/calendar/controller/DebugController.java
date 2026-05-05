package com.daedalussystems.easySchedule.calendar.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/debug")
public class DebugController {

    @GetMapping("/success")
    public String success(@RequestParam Map<String, String> params) {
        return "SUCCESS: " + params.toString();
    }

    @GetMapping("/error")
    public String error(@RequestParam Map<String, String> params) {
        return "ERROR: " + params.toString();
    }
}