package com.syncvault.fileservice.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Extracts plain text from uploaded files using Apache Tika.
 * Supports PDF, DOCX, TXT and any other format in the Tika standard package.
 * Returns an empty string gracefully if extraction fails.
 */
@Slf4j
@Service
public class DocumentTextExtractor {

    public String extractText(byte[] content, String contentType, String fileName) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            AutoDetectParser  parser  = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata          metadata = new Metadata();

            if (contentType != null) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }
            if (fileName != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            }

            parser.parse(in, handler, metadata, new ParseContext());
            return handler.toString().trim();

        } catch (Exception e) {
            log.warn("Text extraction failed for '{}': {}", fileName, e.getMessage());
            return "";
        }
    }

    public String extractText(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            AutoDetectParser  parser  = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1); // no character limit
            Metadata          metadata = new Metadata();

            if (file.getContentType() != null) {
                metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
            }
            if (file.getOriginalFilename() != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
            }

            parser.parse(in, handler, metadata, new ParseContext());
            return handler.toString().trim();

        } catch (Exception e) {
            log.warn("Text extraction failed for '{}': {}", file.getOriginalFilename(), e.getMessage());
            return "";
        }
    }
}
