package com.navan.travel;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String,Object>> domain(ApiException e) {
        Map<String,Object> body = new LinkedHashMap<>(); body.put("error",e.getMessage());
        if (e.existingId != null) body.put("id",e.existingId);
        return ResponseEntity.status(e.status).body(body);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> malformed(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error","Malformed JSON or invalid parameter"));
    }
    @ExceptionHandler(TransientDataAccessException.class)
    public ResponseEntity<Map<String,String>> busy(Exception e) {
        return ResponseEntity.status(409).body(Map.of("error","Concurrent database operation; retry the request"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,String>> integrity(Exception e) {
        return ResponseEntity.status(409).body(Map.of("error","Database constraint conflict"));
    }
}
