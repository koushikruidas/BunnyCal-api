package io.bunnycal.calendar.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public class DebugController {

    @GetMapping("/success")
    @ResponseStatus(HttpStatus.GONE)
    public String success() {
        return "Debug endpoint disabled";
    }

    @GetMapping("/error")
    @ResponseStatus(HttpStatus.GONE)
    public String error() {
        return "Debug endpoint disabled";
    }
}
