package com.lianyu.web.util;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.ImageUploadValidator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageUploadValidatorTest {

  @Test
  void rejectsHtmlDisguisedAsUpload() {
    byte[] html = "<html><body>not-an-image</body></html>".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        BusinessException.class,
        () -> ImageUploadValidator.validateAndReencode(new ByteArrayInputStream(html), html.length));
  }
}
