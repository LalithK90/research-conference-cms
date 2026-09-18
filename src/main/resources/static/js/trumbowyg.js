$(document).ready(function () {
    var csrfToken = $('meta[name="_csrf"]').attr('content');
    var csrfHeader = $('meta[name="_csrf_header"]').attr('content');
    var csrfHeaders = {};
    if (csrfToken && csrfHeader) {
        csrfHeaders[csrfHeader] = csrfToken;
    }

    var fullEditorCfg = {
        btns: [
            ['viewHTML'],
            ['undo', 'redo'],
            ['formatting'],
            ['strong', 'em', 'underline'],
            ['link'],
            ['insertImage', 'upload'],
            ['unorderedList', 'orderedList'],
            ['table'],
            ['removeformat'],
            ['fullscreen']
        ],
        plugins: {
            upload: {
                serverPath: '/admin/media/upload',
                headers: csrfHeaders
            }
        }
    };

    var basicEditorCfg = {
        btns: [
            ['strong', 'em', 'underline'], ['unorderedList', 'orderedList'],
            ['link'], ['h2', 'h3', 'h4'], ['removeformat'], ['viewHTML']
        ]
    };

    $('.trumbowyg-full-editor').trumbowyg(fullEditorCfg);
    $('.trumbowyg-basic-editor').trumbowyg(basicEditorCfg);
});
