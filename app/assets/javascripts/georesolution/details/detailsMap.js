define(['georesolution/common', 'common/map', 'common/annotationContext', 'georesolution/details/pinControl'], function(common, MapBase, AnnotationContext, PinControl) {
  
  var parentEl,
      locatedResults = {},
      resultLayers = {},
      leftPadding = 0; 
  
  var DetailsMap = function(mapDiv, overlayDiv) {
    var self = this,
    
        /** HTML template for the annotation popup **/
        popupTemplate = '<div>' +
                        '  <p class="quote">' +
                        '    <span class="pre"></span><em class="toponym"></em><span class="post"></span>' +
                        '  </p>' +
                        '  <p class="matched-to">' +
                        '    <strong class="label"></strong><br/>' +
                        '    <small class="names"></small><br/>' +
                        '    <a class="gazetteer-id" target="_blank"></a>' +
                        '  </p>' +
                        '  <div class="actions">' +
                        '    <div class="btn labeled verify"><span class="icon">&#xf14a;</span> OK</div>' +
                        '    <div class="btn labeled alternatives">Alternatives</div>' +
                        '    <div class="btn labeled skip-next">Next <span class="icon">&#xf105;</span></div>' +
                        '  </div>' +
                        '</div>',
        
        // Popup for the current annotation
        createPopup = function(place, annotationsWithContext) {          
          var annotation,
              status,
              element = jQuery(popupTemplate),
              quoteToponym = element.find('.toponym'),
              quotePre = element.find('.pre'),
              quotePost = element.find('.post'),
              matchedURI = element.find('.gazetteer-id'),
              matchedLabel = element.find('.label'),
              matchedNames = element.find('.names'),
              btnOk = element.find('.btn.verify'),
              btnAlternatives = element.find('.btn.alternatives'),
              btnSkip = element.find('.btn.skip-next');

          if (annotations.length > 0) { // Should always be the case
            annotation = annotationsWithContext[0][0];
            status = annotation.status.toLowerCase();
            context = annotationsWithContext[0][1];
            
            quoteToponym.html(annotation.toponym);
            quoteToponym.addClass(status);
            
            context.fetchContentPreview(function(preview) {
              var pre = AnnotationContext.truncateWordsRight(preview.pre, 4),
                  post = AnnotationContext.truncateWords(preview.post, 4);
                  
              quotePre.html('...' + pre);
              quotePost.html(post + '...');             
            });
            
            matchedLabel.html(place.title + common.Utils.categoryTag(place.category));
            matchedNames.html(place.names.slice(0, 8).join(', '));
            matchedURI.attr('href', place.uri);
            matchedURI.html(common.Utils.formatGazetteerURI(place.uri));
            
            if (status === 'verified')
              btnOk.hide();
            else
              btnOk.click(function() { self.fireEvent('verify'); });
              
            btnAlternatives.click(function() { 
              self.map.closePopup();
              self.fireEvent('findAlternatives'); 
            });
            btnSkip.click(function() { self.fireEvent('skipNext'); }); 

            return element[0];
          }
        },
        
        /** A control that blocks popups, to force the map from moving **/
        pinControl, // To be initialized after the map
        
        isPinned = function() {
          if (pinControl)
            return pinControl.isEnabled();
          else
            false;
        };
            
    $(mapDiv).on('click', '.gazetteer-id', function(e) {
      e.stopPropagation();
      self.fireEvent('selectSearchresult', locatedResults[e.target.href].result);
      return false;
    });
    
    parentEl = jQuery(mapDiv);
    
    MapBase.apply(this, [ mapDiv, createPopup, false, false, 'topright' ]);
    
    // Initialize the pin control
    pinControl = new PinControl(this.map, { position: 'topright' });
    this.isPinned = isPinned;
    
    // Just disable the keyboard - we don't need it, and instead want arrow
    // keys to skip through the toponym list, not move the map 
    this.map.keyboard.disable();
  }
  DetailsMap.prototype = Object.create(MapBase.prototype);
  
  DetailsMap.prototype.height = function() {
    return parentEl.height();
  };
  
  DetailsMap.prototype.setLayerVisibility = function(name, visible) {
    var layer = resultLayers[name];
    if (layer) {
      if (visible)
        this.map.addLayer(layer);
      else
        this.map.removeLayer(layer);
    }
  };
  
  DetailsMap.prototype.setLeftPadding = function(padding) {
    leftPadding = padding;
  };
  
  DetailsMap.prototype.showSearchresults = function(resultsByGazetteer) {
    var self = this;
    
    jQuery.each(resultsByGazetteer, function(gazetteer, results) {
      // Create new layer for each gazetteer
      var layer = L.featureGroup();
      layer.addTo(self.map);
      resultLayers[gazetteer] = layer;
    
      // Now add the result markers
      jQuery.each(results, function(idx, result) {
        var marker,
            tempBounds = result.temporal_bounds,
            timespan = (tempBounds) ? ' (' + tempBounds.start + ' - ' + tempBounds.end + ')' : '';
      
        if (result.coordinate) {
          marker = L.marker(result.coordinate);            
          // marker = L.geoJson({ 'type': 'Feature', 'geometry': result.geometry });
          marker.bindPopup(
            '<div class="search-result-popup">' + 
            '  <strong>' + result.title + timespan + '</strong>' + common.Utils.categoryTag(result.category) +
            '  <br/><small>' + result.names.slice(0, 8).join(', ') + '</small><br/>' +
            '  <p>' +
            '    <strong>Correct?</strong><br/>Assign to ' + 
            '    <a href="' + result.uri + '" class="gazetteer-id" title="Click to confirm" onclick="return false;">' +
            '      <span class="icon">&#xf14a;</span> ' + common.Utils.formatGazetteerURI(result.uri) + 
            '    </a>' +
            '  </p>' +
            '</div>');

          layer.addLayer(marker);
          locatedResults[result.uri] = { result: result, marker: marker };
        } 
      });
    });
  };
  
  DetailsMap.prototype.selectSearchresult = function(uri) {
    var result = locatedResults[uri], popup;
    if (result) {
      popupOptions = result.marker.getPopup().options;
      popupOptions.autoPanPadding = [leftPadding + 5, 5, 5, 5];      
      result.marker.openPopup();
    } else {
      this.map.closePopup();
    }
  }
  
  DetailsMap.prototype.fitToSearchresults = function() {
    var self = this, 
        searchBounds,
        annotationBounds = this.getAnnotationBounds();
    
    jQuery.each(resultLayers, function(name, layer) {
      var bounds = layer.getBounds();
      if (bounds.isValid()) {
        if (searchBounds)
          searchBounds.extend(bounds);
        else
          searchBounds = bounds;
      }
    });
    
    if (searchBounds.isValid()) {
      if (annotationBounds.isValid())
        searchBounds.extend(annotationBounds);
        
      this.map.fitBounds(searchBounds, { minZoom: self.getCurrentMinZoom() });
    }
  };
  
  DetailsMap.prototype.clearSearchresults = function() {
    jQuery.each(resultLayers, function(gazetteer, layer) {
      layer.clearLayers();
    });
  };
  
  DetailsMap.prototype.invalidateSize = function(animate) {
    this.map.invalidateSize(animate);
  };
  
  DetailsMap.prototype.destroy = function() {
    this.clearSearchresults();
    
    locatedResults = {};
    resultLayers = {};
    
    MapBase.prototype.destroy.call(this);
  };
  
  return DetailsMap;
    
});
