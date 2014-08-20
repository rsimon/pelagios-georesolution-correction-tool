define(['config', 'editor'], function(config, Editor) {
  
  var layer,                   // the map layer
      annotations = [],        // the annotations
      currentHighlight = false, // currently highlighted annotation (if any)
      editor = new Editor(),
      TWO_PI = 2 * Math.PI; 
  
  var highlightAnnotation = function(annotation, x, y) {
    currentHighlight = annotation;    
    if (annotation) {
      editor.show(annotation, x, y);
    } else {
      editor.hide();
    }
    
    layer.getSource().dispatchChangeEvent();
  }
  
  var redraw = function(extent, resolution, pixelRatio, size, projection) {    
    var canvas = document.createElement('canvas');
    canvas.width = size[0];
    canvas.height = size[1];

    var ctx = canvas.getContext('2d');
    ctx.fillStyle = config.MARKER_COLOR;
    ctx.strokeStyle = config.MARKER_COLOR;
    ctx.lineWidth = config.MARKER_LINE_WIDTH;
    
    var draw = function(annotation) {
      var geometry = annotation.shapes[0].geometry;
      var viewportX = (geometry.x - extent[0]) / resolution;
      var viewportY = (geometry.y + extent[3]) / resolution;
      var viewportLength = geometry.l / resolution;
      
      var dx = Math.cos(geometry.a) * viewportLength;
      var dy = Math.sin(geometry.a) * viewportLength;
      
      ctx.beginPath();
      ctx.arc(viewportX, viewportY, config.MARKER_CIRCLE_RADIUS, 0, TWO_PI);
      ctx.fill();
      ctx.closePath();
        
      ctx.beginPath();
      ctx.moveTo(viewportX, viewportY);
      ctx.lineTo(viewportX + dx, viewportY - dy);
      ctx.stroke();
      ctx.closePath();      
    };

    $.each(annotations, function(idx, annotation) {  
      draw(annotation);
    });
    
    if (currentHighlight) {
      ctx.fillStyle = config.MARKER_HI_COLOR;
      ctx.strokeStyle = config.MARKER_HI_COLOR;
      draw(currentHighlight);
    }
    
    return canvas;
  }
  
  var AnnotationLayer = function(map) {
    layer = new ol.layer.Image({
      source: new ol.source.ImageCanvas({
        canvasFunction: redraw,
        projection: 'ZOOMIFY'
      })
    });
    map.addLayer(layer);
    
    /** THIS IS A TEMPORARY HACK ONLY **/
    map.on('pointermove', function(e) {
      var maxDistance = map.getView().getResolution() * 10;
      
      var hovered = $.grep(annotations, function(annotation) {
        var geometry = annotation.shapes[0].geometry;
        var dx = Math.abs(e.coordinate[0] - geometry.x);
        var dy = Math.abs(e.coordinate[1] + geometry.y);        
        return (dx < maxDistance && dy < maxDistance);
      });
      
      if (hovered.length > 0) {
        if (currentHighlight) {
          if (currentHighlight.id != hovered[0].id) {
            // Change highlight from one annotation to next
            highlightAnnotation(hovered[0], e.pixel[0], e.pixel[1]);
          }
        } else {
          // No previous highlight - highlight annotation under mouse
          highlightAnnotation(hovered[0], e.pixel[0], e.pixel[1]);
        }
      } else {
        // No annotation under mouse - clear highlights
        highlightAnnotation(false);
      }
    });
    /** THIS IS A TEMPORARY HACK ONLY **/
  }
  
  AnnotationLayer.prototype.addAnnotations = function(a) {
    if ($.isArray(a)) {
      $.each(a, function(idx, annotation) {
        annotations.push(annotation);
      });
    } else {
      annotations.push(a);
    }
    
    layer.getSource().dispatchChangeEvent();
  }
  
  return AnnotationLayer;
  
});