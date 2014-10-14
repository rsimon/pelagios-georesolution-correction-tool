define(['imageannotation/config', 'imageannotation/viewer/annotationLayer'], function(Config, AnnotationLayer) {
  
  var Viewer = function(divId, eventBroker) {      
    var projection = new ol.proj.Projection({
          code: 'ZOOMIFY',
          units: 'pixels',
          extent: [0, 0, Config.width, Config.height]
        }),
        
        tileSource = new ol.source.Zoomify({
          url: Config.url,
          size: [ Config.width, Config.height ]
        }),
        
        map = new ol.Map({
          target: divId,
          layers: [ new ol.layer.Tile({ source: tileSource }) ],
          view: new ol.View({
            projection: projection,
            center: [Config.width / 2, - Config.height / 2],
            zoom: 0,
            minResolution: 0.5
          })
        }),
        
        annotationLayer = new AnnotationLayer($('#' + divId).parent(), map, eventBroker);    
        
    // map.addLayer(annotationLayer.getLayer());
    map.getView().fitExtent([0, - Config.height, Config.width, 0], map.getSize());
  };
  
  return Viewer;
  
});
