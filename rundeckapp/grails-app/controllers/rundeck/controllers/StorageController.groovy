package rundeck.controllers

import com.dtolabs.rundeck.app.support.StorageParams
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.storage.ResourceMeta
import com.dtolabs.rundeck.core.storage.StorageAuthorizationException
import com.dtolabs.rundeck.server.plugins.storage.KeyStorageLayer
import org.rundeck.storage.api.Resource
import org.rundeck.storage.api.StorageException
import org.springframework.web.multipart.MultipartHttpServletRequest
import rundeck.filters.ApiRequestFilters
import rundeck.services.ApiService
import rundeck.services.FrameworkService
import rundeck.services.StorageService

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class StorageController extends ControllerBase{
    public static final String RES_META_RUNDECK_CONTENT_TYPE = 'Rundeck-content-type'
    public static final String RES_META_RUNDECK_CONTENT_SIZE = 'Rundeck-content-size'
    public static final String RES_META_RUNDECK_CONTENT_MASK = 'Rundeck-content-mask'
    public static final String RES_META_RUNDECK_KEY_TYPE = 'Rundeck-key-type'
    public static final Map<String,String> RES_META_RUNDECK_OUTPUT = [
            (RES_META_RUNDECK_CONTENT_TYPE):"contentType",
            (RES_META_RUNDECK_CONTENT_SIZE):"contentLength",
            (RES_META_RUNDECK_CONTENT_MASK): RES_META_RUNDECK_CONTENT_MASK,
            (RES_META_RUNDECK_KEY_TYPE): RES_META_RUNDECK_KEY_TYPE,
            (KeyStorageLayer.RUNDECK_DATA_TYPE): KeyStorageLayer.RUNDECK_DATA_TYPE,
    ]
    StorageService storageService
    ApiService apiService
    FrameworkService frameworkService
    static allowedMethods = [
            apiKeys: ['GET','POST','PUT','DELETE'],
            keyStorageAccess:['GET'],
            keyStorageDownload:['GET'],
            keyStorageUpload:['POST'],
            keyStorageDelete:['POST']
    ]

    private def pathUrl(path){
        def uriString = "/api/${ApiRequestFilters.API_CURRENT_VERSION}/incubator/storage/$path"
        if ("${path}".startsWith('keys/') || path.toString() == 'keys') {
            uriString = "/api/${ApiRequestFilters.API_CURRENT_VERSION}/storage/$path"
        }
        return createLink(absolute: true, uri: uriString)
    }
    private def jsonRenderResource(builder,Resource res, dirlist=[]){
        builder.with{
            path = res.path.toString()
            type = res.directory ? 'directory' : 'file'
            if(!res.directory){
                name = res.path.name
            }
            url = pathUrl(res.path)
            if (!res.directory) {
                def meta1 = res.contents.meta
                if (meta1) {
                    def bd = delegate
                    def meta=[:]
                    RES_META_RUNDECK_OUTPUT.each{k,v->
                        if(meta1[k]){
                            meta[k]= meta1[k]
                        }
                    }
                    if(meta){
                        bd.meta=meta
                    }

                }
            }
            if(dirlist){
                delegate.'resources' = array {
                    def builder2 = delegate
                    dirlist.each { diritem ->
                        builder2.element {
                            jsonRenderResource(delegate, diritem,[])
                        }
                    }
                }
            }
        }
    }
    private def xmlRenderResource(builder,Resource res,dirlist=[]){
        def map=[path: res.path.toString(),
                type: res.directory ? 'directory' : 'file',
                url: pathUrl(res.path)]
        if(!res.directory){
            map.name= res.path.name
        }
        builder.'resource'(map) {
            if (!res.directory) {
                def data = res.contents.meta
                delegate.'resource-meta' {
                    def bd = delegate
                    RES_META_RUNDECK_OUTPUT.each { k, v ->
                        if (res.contents.meta[k]) {
                            bd."${k}"(res.contents.meta[k])
                        }
                    }
                }
            }else if (dirlist){
                delegate.'contents'(count: dirlist.size()) {
                    def builder2 = delegate
                    dirlist.each { diritem ->
                        xmlRenderResource(builder2, diritem,[])
                    }
                }
            }
        }
    }

    private def renderDirectory(HttpServletRequest request, HttpServletResponse response, Resource resource,
                                Set<Resource<ResourceMeta>> dirlist) {
        withFormat {
            json {
                render(contentType: 'application/json') {
                    jsonRenderResource(delegate, resource,dirlist)
                }
            }
            xml {
                render {
                    xmlRenderResource(delegate, resource, dirlist)
                }
            }
        }
    }
    private def renderResourceFile(HttpServletRequest request, HttpServletResponse response, Resource resource, boolean forceDownload=false) {
        def contents = resource.contents
        def meta = contents?.meta
        def resContentType= meta?.getAt(RES_META_RUNDECK_CONTENT_TYPE)
        def cmask=meta?.getAt(RES_META_RUNDECK_CONTENT_MASK)?.split(',') as Set
        //
        def maskContent=cmask?.contains('content')

        def askedForContent= forceDownload || resContentType && request.getHeader('Accept')?.contains(resContentType)
        def anyContent= response.format == 'all'

        if (askedForContent && maskContent) {
            //content is masked, issue 403
            response.status = 403
            return renderError("unauthorized")
        }
        if((askedForContent || anyContent) && !maskContent) {
            response.contentType=resContentType
            if(forceDownload){
                def filename= resource.path.name
                response.setHeader("Content-Disposition", "attachment; filename=\"${filename}\"")
            }
            def len=contents.writeContent(response.outputStream)
            response.outputStream.close()
            return
        }

        //render API resource file data
        switch (response.format){
            case 'xml':
                render(contentType: 'application/xml') {
                    xmlRenderResource(delegate, resource)
                }
                break;
            case 'json':
                ///fallthrough json response by default
            default:
                render(contentType: 'application/json') {
                    jsonRenderResource(delegate, resource)
                }
        }
    }

    private Object renderError(String message) {
        def jsonResponseclosure= {
            render(contentType: "application/json") {
                delegate.'error' = message
            }
        }
        if(!(response.format in ['json','xml'])){
            return jsonResponseclosure.call()
        }
        withFormat {
            json(jsonResponseclosure)
            xml {
                render(contentType: "application/xml") {
                    delegate.'error'(message)
                }
            }
        }
    }

    /**
     * non-api action wrapper for apiKeys method
     */
    public def keyStorageAccess(StorageParams storageParams){
        params.resourcePath = "/keys/${params.resourcePath ?: ''}"
        getResource(storageParams)
    }
    /**
     * non-api action wrapper for apiKeys method
     */
    public def keyStorageDownload(){
        if(!params.resourcePath.startsWith("keys")){
            params.resourcePath = "/keys/${params.resourcePath ?: ''}"
        }
        getResource(true)
    }
    /**
     * non-api action wrapper for apiKeys method
     */
    public def keyStorageUpload(StorageParams storageParams){
        if (storageParams.hasErrors()) {
            return renderErrorView([beanErrors: storageParams.errors])
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        def resourcePath = params.resourcePath
        def valid=false
        withForm {
            valid=true
        }.invalidToken{
            request.errorCode= 'request.error.invalidtoken.message'
            return renderErrorView([:])
        }
        if(!valid){
            return
        }
        def contentType= null
        def contentLength = -1
        def inputStream = null

        if (params.uploadKeyType == 'public') {
            contentType = KeyStorageLayer.PUBLIC_KEY_MIME_TYPE
        } else if (params.uploadKeyType == 'private') {
            contentType = KeyStorageLayer.PRIVATE_KEY_MIME_TYPE
        } else if (params.uploadKeyType == 'password') {
            contentType = KeyStorageLayer.PASSWORD_MIME_TYPE
        } else {
            //invalid
            request.errorCode = 'api.error.parameter.invalid'
            request.errorArgs = [params.uploadKeyType, 'uploadKeyType']
            return renderErrorView([:])
        }
        if( !(params.inputType in ['file','text'])){
            request.errorCode = 'api.error.parameter.not.inList'
            request.errorArgs = [params.inputType, 'inputType']
            return renderErrorView([:])
        }

        def hasUploadedFile = request instanceof MultipartHttpServletRequest && params.inputType == 'file' && request.getFile('storagefile')
        if (!params.fileName && !hasUploadedFile) {
            //invalid
            request.errorCode = 'api.error.parameter.required'
            request.errorArgs = ['fileName']
            return renderErrorView([:])
        }
        def filename = params.fileName

        if(params.inputType == 'text' && params.uploadKeyType in ['public','private'] ){
            //store a public/private key
            if(!params.uploadText){
                //invalid
                request.errorCode = 'api.error.parameter.required'
                request.errorArgs = ['uploadText']
                return renderErrorView([:])
            }

            def inputBytes = params.uploadText.bytes
            inputStream = new ByteArrayInputStream(inputBytes)
            contentLength= inputBytes.length
        }else if(params.inputType == 'text' && params.uploadKeyType == 'password' ){
            //store a password
            if (!params.uploadPassword) {
                //invalid
                request.errorCode = 'api.error.parameter.required'
                request.errorArgs = ['uploadPassword']
                return renderErrorView([:])
            }
            def inputBytes = params.uploadPassword.bytes
            inputStream = new ByteArrayInputStream(inputBytes)
            contentLength= inputBytes.length
        }else if (hasUploadedFile) {
            //nb: don't allow arbitrary content type
//            contentType = request.getFile('storagefile').getContentType()
            contentLength = request.getFile('storagefile').getSize()
            inputStream = request.getFile('storagefile').inputStream
            if(!filename){
                filename = request.getFile('storagefile').originalFilename
            }
        }else{
            //no file uploaded
            request.errorCode = 'api.error.upload.missing'
            request.errorArgs = ['storagefile']
            return renderErrorView([:])
        }
        resourcePath = resourcePath + '/' + filename
        def newparams=new StorageParams()
        newparams.resourcePath=resourcePath
        newparams.validate()
        if(newparams.hasErrors()){
            return renderErrorView([beanErrors: newparams.errors])
        }

        //TODO: overwrite
        if (storageService.hasResource(authContext, resourcePath)) {
            response.status = 409
            return renderError("resource already exists: ${resourcePath}")
        } else if (storageService.hasPath(authContext, resourcePath)) {
            response.status = 409
            return renderError("directory already exists: ${resourcePath}")
        }
        Map<String, String> map = [
                (RES_META_RUNDECK_CONTENT_TYPE): contentType,
                (RES_META_RUNDECK_CONTENT_SIZE): contentLength,
        ]
        try {
            //TODO: overwrite
            def resource = storageService.createResource(authContext, resourcePath, map, inputStream)
            return redirect(controller: 'menu', action: 'storage', params: [project: params.project,resourcePath:resourcePath])
        } catch (StorageAuthorizationException e) {
            log.error("Unauthorized: resource ${resourcePath}: ${e.message}")
            response.status= HttpServletResponse.SC_FORBIDDEN
            request.errorCode = 'api.error.item.unauthorized'
            request.errorArgs = [e.event.toString(), 'Path', e.path.toString()]
            return renderErrorView([:])
        } catch (StorageException e) {
            log.error("Error creating resource ${resourcePath}: ${e.message}")
            log.debug("Error creating resource ${resourcePath}", e)
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            request.errorMessage = e.message
            return renderErrorView([:])
        }
    }
    /**
     * non-api action wrapper for deleteResource method
     */
    public def keyStorageDelete() {
        if (!params.resourcePath.startsWith("keys")) {
            params.resourcePath = "/keys/${params.resourcePath ?: ''}"
        }
        def valid = false
        withForm {
            valid = true
            g.refreshFormTokensHeader()
        }.invalidToken {
            def message = g.message(code: 'request.error.invalidtoken.message')
            log.error(message)
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'request.error.invalidtoken.message'
            ])
        }
        if (!valid) {
            return
        }
        deleteResource()
    }
    /**
     * Handle resource requests to the /ssh-key path
     * @return
     */
    def apiKeys() {
        if(!apiService.requireVersion(request,response,ApiRequestFilters.V11)){
            return
        }
        params.resourcePath = "/keys/${params.resourcePath?:''}"
        switch (request.method) {
            case 'POST':
                apiPostResource()
                break
            case 'PUT':
                apiPutResource()
                break
            case 'GET':
                apiGetResource()
                break
            case 'DELETE':
                apiDeleteResource()
                break
        }
    }

    def apiPostResource() {
        if (!apiService.requireApi(request, response)) {
            return
        }
        return postResource()
    }
    private def postResource() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        String resourcePath = params.resourcePath
        if (storageService.hasResource(authContext, resourcePath)) {
            response.status = 409
            return renderError("resource already exists: ${resourcePath}")
        }else if(storageService.hasPath(authContext, resourcePath)){
            response.status = 409
            return renderError("directory already exists: ${resourcePath}")
        }
        Map<String,String> map = [
                (RES_META_RUNDECK_CONTENT_TYPE): request.contentType,
                (RES_META_RUNDECK_CONTENT_SIZE): Integer.toString(request.contentLength),
        ] + (request.resourcePostMeta?:[:])
        try{
            def resource = storageService.createResource(authContext,resourcePath, map, request.inputStream)
            response.status=201
            renderResourceFile(request,response,resource)
        } catch (StorageAuthorizationException e) {
            log.error("Unauthorized: resource ${resourcePath}: ${e.message}")
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized',
                    args: [e.event.toString(), 'Path', e.path.toString()]
            ])
        } catch (StorageException e) {
            log.error("Error creating resource ${resourcePath}: ${e.message}")
            log.debug("Error creating resource ${resourcePath}", e)
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message: e.message
            ])
        }
    }


    def apiDeleteResource() {
        if (!apiService.requireApi(request, response)) {
            return
        }
        return deleteResource()
    }
    private def deleteResource() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        String resourcePath = params.resourcePath
        if(!storageService.hasResource(authContext, resourcePath)) {
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist',
                    args: ['Resource', resourcePath]
            ])
        }
        try{
            def deleted = storageService.delResource(authContext, resourcePath)
            if(deleted){
                render(status: HttpServletResponse.SC_NO_CONTENT)
            }else{
                apiService.renderErrorFormat(response, [
                        status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        message: "Resource was not deleted: ${resourcePath}"
                ])
            }
        } catch (StorageAuthorizationException e) {
            log.error("Unauthorized: resource ${resourcePath}: ${e.message}")
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized',
                    args: [e.event.toString(), 'Path', e.path.toString()]
            ])
        } catch (StorageException e) {
            log.error("Error deleting resource ${resourcePath}: ${e.message}")
            log.debug("Error deleting resource ${resourcePath}", e)
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message: e.message
            ])
        }
    }

    def apiPutResource() {
        if (!apiService.requireApi(request, response)) {
            return
        }
        return putResource()
    }
    private def putResource() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        String resourcePath = params.resourcePath
        def found = storageService.hasResource(authContext, resourcePath)
        if (!found) {
            response.status = 404
            return renderError("resource not found: ${resourcePath}")
        }
        Map<String, String> map = [
                (RES_META_RUNDECK_CONTENT_TYPE): request.contentType,
                (RES_META_RUNDECK_CONTENT_SIZE): Integer.toString(request.contentLength),
        ] + (request.resourcePostMeta ?: [:])
        try {
            def resource = storageService.updateResource(authContext,resourcePath, map, request.inputStream)
            return renderResourceFile(request,response,resource)
        } catch (StorageAuthorizationException e) {
            log.error("Unauthorized: resource ${resourcePath}: ${e.message}")
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized',
                    args: [e.event.toString(), 'Path', e.path.toString()]
            ])
        } catch (StorageException e) {
            log.error("Error putting resource ${resourcePath}: ${e.message}")
            log.debug("Error putting resource ${resourcePath}", e)
            apiService.renderErrorFormat(response,[
                    status:HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message:e.message
            ])
        }
    }

    def apiGetResource(StorageParams storageParams) {
        if (!apiService.requireApi(request, response)) {
            return
        }
        return getResource(storageParams)
    }
    private def getResource(StorageParams storageParams,boolean forceDownload=false) {
        if (storageParams.hasErrors()) {
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.invalid.request',
                    args: [storageParams.errors.allErrors.collect { g.message(error: it) }.join(",")]
            ])
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        String resourcePath = params.resourcePath
        def found = storageService.hasPath(authContext, resourcePath)
        if(!found){
            response.status=404
            return renderError("resource not found: ${resourcePath}")
        }
        try{
            def resource = storageService.getResource(authContext, resourcePath)
            if (resource.directory) {
                //list directory and render resources
                def dirlist = storageService.listDir(authContext, resourcePath)
                return renderDirectory(request, response, resource,dirlist)
            } else {
                return renderResourceFile(request, response, resource, forceDownload)
            }
        } catch (StorageAuthorizationException e) {
            log.error("Unauthorized: resource ${resourcePath}: ${e.message}")
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_FORBIDDEN,
                    code: 'api.error.item.unauthorized',
                    args: [e.event.toString(), 'Path', e.path.toString()]
            ])
        }catch (StorageException e) {
            log.error("Error reading resource ${resourcePath}: ${e.message}")
            log.debug("Error reading resource ${resourcePath}",e)
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message: e.message
            ])
        }
    }
}
