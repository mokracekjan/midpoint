/*
 * Copyright (c) 2010-2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

$(window).on('load', function() {
    //dom not only ready, but everything is loaded MID-3668
    $("body").removeClass("custom-hold-transition");

    initAjaxStatusSigns();

    Wicket.Event.subscribe('/ajax/call/failure', function( attrs, jqXHR, textStatus, jqEvent, errorThrown ) {
        console.error("Ajax call failure:\n" + JSON.stringify(attrs.target.location)
            + "\nStatus:\n" + JSON.stringify(textStatus));
    });

    fixContentHeight();
    $(window, ".wrapper").resize(function () {
        fixContentHeight();
    });
});

// I'm not sure why sidebar has 15px padding -> and why I had to use 10px constant here [lazyman]
function fixContentHeight() {
    if ($(".main-footer").length > 0) {
        return;
    }

    var window_height = $(window).height();
    var sidebar_height = $(".sidebar").height() || 0;

    if (window_height < sidebar_height) {
        $(".content-wrapper, .right-side").css('min-height', sidebar_height + 10); // footer size
    }
}

function clickFuncWicket6(eventData) {
    var clickedElement = (window.event) ? event.srcElement : eventData.target;
    if ((clickedElement.tagName.toUpperCase() == 'BUTTON'
        || clickedElement.tagName.toUpperCase() == 'A'
        || clickedElement.parentNode.tagName.toUpperCase() == 'A'
        || (clickedElement.tagName.toUpperCase() == 'INPUT'
        && (clickedElement.type.toUpperCase() == 'BUTTON'
        || clickedElement.type.toUpperCase() == 'SUBMIT')))
        && clickedElement.parentNode.id.toUpperCase() != 'NOBUSY'
        && clickedElement.disabled == 'false') {
        showAjaxStatusSign();
    }
}

function initAjaxStatusSigns() {
    document.getElementsByTagName('body')[0].onclick = clickFuncWicket6;
    hideAjaxStatusSign();
    Wicket.Event.subscribe('/ajax/call/beforeSend', function( attributes, jqXHR, settings ) {
        showAjaxStatusSign();
    });
    Wicket.Event.subscribe('/ajax/call/complete', function( attributes, jqXHR, textStatus) {
        hideAjaxStatusSign();
    });
}

function showAjaxStatusSign() {
    document.getElementById('ajax_busy').style.visibility = 'visible';
}

function hideAjaxStatusSign() {
    document.getElementById('ajax_busy').style.visibility = 'hidden';
}

/**
 * InlineMenu initialization function
 */
function initInlineMenu(menuId, hideByDefault) {
    var cog = $('#' + menuId).find('ul.cog');
    var menu = cog.children().find('ul.dropdown-menu');

    var parent = cog.parent().parent();     //this is inline menu div
    if (!hideByDefault && !isCogInTable(parent)) {
        return;
    }

    if (isCogInTable(parent)) {
        //we're in table, we now look for <tr> element
        parent = parent.parent('tr');
    }

    // we only want to hide inline menus that are in table <td> element,
    // inline menu in header must be visible all the time, or every menu
    // that has hideByDefault flag turned on
    cog.hide();

    parent.hover(function () {
        //over
        cog.show();
    }, function () {
        //out
        if (!menu.is(':visible')) {
            cog.hide();
        }
    });
}

function isCogInTable(inlineMenuDiv) {
    return inlineMenuDiv.hasClass('cog') && inlineMenuDiv[0].tagName.toLowerCase() == 'td';
}

function updateHeight(elementId, add, substract) {
    updateHeightReal(elementId, add, substract);
    $(window).resize(function() {
        updateHeightReal(elementId, add, substract);
    });
}

function updateHeightReal(elementId, add, substract) {
    $('#' + elementId).css("height","0px");

    var documentHeight = $(document).innerHeight();
    var elementHeight = $('#' + elementId).outerHeight(true);
    var mainContainerHeight = $('section.content-header').outerHeight(true)
        + $('section.content').outerHeight(true) + $('footer.main-footer').outerHeight(true)
        + $('header.main-header').outerHeight(true);

    console.log("Document height: " + documentHeight + ", mainContainer: " + mainContainerHeight);

    var height = documentHeight - mainContainerHeight - elementHeight - 1;
    console.log("Height clean: " + height);

    if (substract instanceof Array) {
        for (var i = 0; i < substract.length; i++) {
            console.log("Substract height: " + $(substract[i]).outerHeight(true));
            height -= $(substract[i]).outerHeight(true);
        }
    }
    if (add instanceof Array) {
        for (var i = 0; i < add.length; i++) {
            console.log("Add height: " + $(add[i]).outerHeight(true));
            height += $(add[i]).outerHeight(true);
        }
    }
    console.log("New css height: " + height);
    $('#' + elementId).css("height", height + "px");
}

/**
 * Used in TableConfigurationPanel (table page size)
 *
 * @param buttonId
 * @param popoverId
 * @param positionId
 */
function initPageSizePopover(buttonId, popoverId, positionId) {
    console.log("initPageSizePopover('" + buttonId + "','" + popoverId + "','" + positionId +"')");

    var button = $('#' + buttonId);
    button.click(function () {
        var popover = $('#' + popoverId);

        var positionElement = $('#' + positionId);
        var position = positionElement.position();

        var top = position.top + parseInt(positionElement.css('marginTop'));
        var left = position.left + parseInt(positionElement.css('marginLeft'));
        var realPosition = {top: top, left: left};

        var left = realPosition.left - popover.outerWidth();
        var top = realPosition.top + button.outerHeight() / 2 - popover.outerHeight() / 2;

        popover.css("top", top);
        popover.css("left", left);

        popover.toggle();
    });
}

/**
 * Used in SearchPanel for advanced search, if we want to store resized textarea dimensions.
 *
 * @param textAreaId
 */
function storeTextAreaSize(textAreaId) {
    console.log("storeTextAreaSize('" + textAreaId + "')");

    var area = $('#' + textAreaId);
    $.textAreaSize = [];
    $.textAreaSize[textAreaId] = {
        height: area.height(),
        width: area.width(),
        position: area.prop('selectionStart')
    }
}

/**
 * Used in SearchPanel for advanced search, if we want to store resized textarea dimensions.
 *
 * @param textAreaId
 */
function restoreTextAreaSize(textAreaId) {
    console.log("restoreTextAreaSize('" + textAreaId + "')");

    var area = $('#' + textAreaId);

    var value = $.textAreaSize[textAreaId];

    area.height(value.height);
    area.width(value.width);
    area.prop('selectionStart', value.position);

    // resize also error message span
    var areaPadding = 70;
    area.siblings('.help-block').width(value.width + areaPadding);
}

/**
 * Used in SearchPanel class
 *
 * @param buttonId
 * @param popoverId
 * @param paddingRight value which will shift popover to the left from center bottom position against button
 */
function toggleSearchPopover(buttonId, popoverId, paddingRight) {
    console.log("Called toggleSearchPopover with buttonId=" + buttonId + ",popoverId="
        + popoverId + ",paddingRight=" + paddingRight);

    var button = $('#' + buttonId);
    var popover = $('#' + popoverId);

    var popovers = button.parents('.search-form').find('.popover:visible').each(function () {
        var id = $(this).attr('id');
        console.log("Found popover with id=" + id);

        if (id != popoverId) {
            $(this).hide(200);
        }
    });

    var position = button.position();

    var left = position.left - (popover.outerWidth() - button.outerWidth()) / 2 - paddingRight;
    var top = position.top + button.outerHeight();

    var offsetLeft = button.offset().left - position.left;

    if ((left + popover.outerWidth() + offsetLeft) > window.innerWidth) {
        left = window.innerWidth - popover.outerWidth() - offsetLeft - 15;
    } else if (left < 0) {
        left = 0;
    }

    popover.css('top', top);
    popover.css('left', left);

    popover.toggle(200);

    //this will set focus to first form field on search item popup
    popover.find('input[type=text],textarea,select').filter(':visible:first').focus();

    //this will catch ESC or ENTER and fake close or update button click
    popover.find('input[type=text],textarea,select').off('keyup.search').on('keyup.search', function(e) {
        if (e.keyCode == 27) {
            popover.find('[data-type="close"]').click();
        } else if (e.keyCode == 13) {
            popover.find('[data-type="update"]').click();
        }
    });
}

/**
 * used in DropDownMultiChoice.java
 *
 * @param compId
 * @param options
 */
function initDropdown(compId, options) {
    $('#' + compId).multiselect(options);
}

// expand/collapse for sidebarMenuPanel
jQuery(function ($) {
    $('.sidebar-menu li.header').on("click", function (e) {
        if ($(this).hasClass('closed')) {
            // expand the panel
            $(this).nextUntil('.header').slideDown();
            $(this).removeClass('closed');
        }
        else {
            // collapse the panel
            $(this).nextUntil('.header').slideUp();
            $(this).addClass('closed');
        }
    });
});

function showPassword(iconElement) {
    var parent = iconElement.closest(".password-parent");
	var input = parent.querySelector("input");

    	if (input.type === "password") {
    		input.type = "text";
    		iconElement.className = 'fa fa-eye-slash';
  		} else {
    		input.type = "password";
    		iconElement.className = 'fa fa-eye';
  		}
}

!function($) {
    $.fn.passwordFieldValidatorPopover = function(inputId, popover) {
        return this.each(function() {

            var parent = $(this).parent();

            var showPopover=function(){
                parent.find(inputId).each(function() {
                    var itemH=$(this).innerHeight() + 27;
                    parent.find(popover).fadeIn(300).css({top:itemH, left:0}).css("display", "block");
                });
            }

            showPopover();
            $(this).on("focus", function(){showPopover();});

            var deletePopover=function(){
        	    parent.find(popover).fadeIn(300).css("display", "none");
            };

            $(this).on("blur", function(){
        	    deletePopover();
            });
        });
    };

    $.fn.passwordValidatorPopover = function(inputId, popover) {
            return this.each(function() {

                var parent = $(this).parent();

                var showPopover=function(){
                    if (parent.find(inputId + ":hover").length != 0) {
                        parent.find(inputId).each(function() {
                            var itemH=$(this).innerHeight() + 9;
                            parent.find(popover).fadeIn(300).css({top:itemH, left:0}).css("display", "block");
                        });
                    }
                }

                $(this).on("mouseenter", function(){showPopover();});

                var deletePopover=function(){
                    parent.find(popover).fadeIn(300).css("display", "none");
                };

                $(this).on("mouseleave", function(){
                    if (parent.find(popover + ":hover").length == 0) {
            	        deletePopover();
            	    }
                });
                parent.find(popover).on("mouseleave", function(){
                    if (parent.find(inputId + ":hover").length == 0) {
                        deletePopover();
                    }
               });
            });
        };
}(window.jQuery);

(function($) {
    $.fn.updateParentClass = function(successClass, parentSuccessClass, parentId, failClass, parentFailClass) {
        var child = this;
        var parent = $("#" + parentId);

        if (child.hasClass(successClass)){
            if (parent.hasClass(parentFailClass)) {
                parent.removeClass(parentFailClass);
            }
            if (!parent.hasClass(parentSuccessClass)) {
                parent.addClass(parentSuccessClass);
            }
        } else if (child.hasClass(failClass)){
            if (parent.hasClass(parentSuccessClass)) {
                parent.removeClass(parentSuccessClass);
            }
            if (!parent.hasClass(parentFailClass)) {
                parent.addClass(parentFailClass);
            }
        }
    }
})(jQuery);

jQuery(function ($) {
    $(document).on("mouseenter", "*[data-toggle='tooltip']", function (e, t) {
        if (typeof $(this).tooltip === "function") {
            var wl = $.fn.tooltip.Constructor.DEFAULTS.whiteList;
            wl['xsd:documentation'] = [];
            var parent = $(this).closest('.wicket-modal');
            var container = "body";
            if (parent.length != 0) {
                container = '#' + parent.attr('id');
            }
            $(this).tooltip({html: true, whiteList: wl, 'container': container});
            $(this).tooltip("show");
            };
    });
});
