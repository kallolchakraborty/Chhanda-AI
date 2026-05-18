import Foundation
import Vision
import AppKit

func performOCR(imagePath: String) -> String {
    let url = URL(fileURLWithPath: imagePath)
    guard let image = NSImage(contentsOf: url) else {
        return "Failed to load image at \(imagePath)"
    }
    guard let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        return "Failed to get CGImage for \(imagePath)"
    }
    
    let requestHandler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    
    do {
        try requestHandler.perform([request])
        guard let observations = request.results else {
            return "No text found"
        }
        let recognizedStrings = observations.compactMap { observation in
            observation.topCandidates(1).first?.string
        }
        return recognizedStrings.joined(separator: "\n")
    } catch {
        return "Error: \(error.localizedDescription)"
    }
}

let arguments = CommandLine.arguments
if arguments.count < 2 {
    print("Usage: swift ocr.swift <image_path>")
    exit(1)
}

let path = arguments[1]
let ocrResult = performOCR(imagePath: path)
print("--- OCR RESULT FOR \(URL(fileURLWithPath: path).lastPathComponent) ---")
print(ocrResult)
print("--------------------------------------------------")
