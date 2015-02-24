define(['imageannotation/viewer/annotations'], function(Annotations) {
  
  var EditorAutoSuggest = function(parentEl, textInput, uriInput) {
    var ENDPOINT_URL = '../../api/search?prefix='+ encodeURIComponent('http://www.maphistory.info/') + '&query=',
    
        MAX_LIST_SIZE = 10,
    
        pendingQuery = false,
        
        /** List element **/
        ul = jQuery('<ul></ul>'),
        
        /** Updates the list of suggestions with new search results **/
        showSearchResults = function(results) {
          var selectedLi = ul.find('li.selected'),
              selectedURI = (selectedLi.length > 0) ? selectedLi.data('uri') : false;
          
          // Remove old results from list - except selected one (if any)
          ul.find('li').not('.selected').remove();
          
          // Add results to list
          jQuery.each(results, function(idx, result) {
            if (selectedURI !== result.uri)
              appendSuggestion(result, false);
          });
        },
        
        /** Displays a single suggestion **/
        appendSuggestion = function(place, selected) {
          var title = place.names.slice(0,5).join(', '),
              li;
              
          if (place.description)
            title += ' (' + place.description + ')';
                
          li = jQuery('<li title="' + title + '" data-uri="' + place.uri + '">' + place.title + '</li>');
          
          if (selected)
            li.addClass('selected');
          
          ul.append(li);
        },
    
        /** Fetches search results from the server and displays them inside the parentEl **/
        getSuggestions = function(chars) {
          if (chars.length > 1) { // Start fetching proposals from 2 chars onwards
            jQuery.getJSON(ENDPOINT_URL + encodeURIComponent(chars).replace('%20', '+AND+') + '*', function(data) {
              var results = data.results.slice(0, MAX_LIST_SIZE);
              if (results.length == 0) {
                // Include fuzzy matches
                jQuery.getJSON(ENDPOINT_URL + encodeURIComponent(chars) + '~', function(data) {
                  showSearchResults(data.results.slice(0, MAX_LIST_SIZE));
                });
              } else {
                showSearchResults(results);
              }
            });
          }
        },
                
        /** Handler function to select a suggestion from the list **/
        toggleSelect = function(li) {
          var selected = li.hasClass('selected');
          if (selected) {
            // User cleared selection
            li.removeClass('selected');
            uriInput.val();
          } else {
            // User set a new selection
            jQuery.each(li.siblings(), function(idx, li) {
              jQuery(li).removeClass('selected');
            });
            li.addClass('selected');
            uriInput.val(li.data('uri'));
          }
        },
        
        /** Shows the autosuggest widget **/
        show = function(annotation) {
          var place = (annotation.place_fixed) ? annotation.place_fixed : annotation.place;
          
          // Reset suggestion list and form field
          ul.html('');
          uriInput.val('');
          
          if (place) {
            appendSuggestion(place, true);
          } else {
            Annotations.fetchDetails(annotation, function(annotation) {
              var place = (annotation.place_fixed) ? annotation.place_fixed : annotation.place;  
              if (place)
                appendSuggestion(place, true);
            });
          }
          parentEl.show();
        },
        
        /** Hides the auto-suggest widget **/
        hide = function() {
          parentEl.hide();
        };
        
    parentEl.append(ul);
    
    // Double click selects the place AND transfers the title to the text field
    ul.on('dblclick', 'li', function(e) {
      var t = jQuery(e.target);
      toggleSelect(t);
      textInput.val(t.html());
    });
    
    // Single click only selects the place
    ul.on('click', 'li', function(e) {
      var t = jQuery(e.target);
      toggleSelect(t);
    });
    
    textInput.keyup(function(e) {
      // Introduce a 200ms wait, in order to not overload the wire
      if (pendingQuery) {
        window.clearTimeout(pendingQuery);
        pedingQuery = false;
      }
       
      var chars = textInput.val();
      pendingQuery = window.setTimeout(function() {
        getSuggestions(chars);
      }, 200);
    }); 
    
    /** Privileged methods **/
    this.show = show;
    this.hide = hide;  
  };
  
  return EditorAutoSuggest;
  
});
  
