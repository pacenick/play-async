/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.play.asyncmvc.example.controllers

import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc._
import uk.gov.hmrc.http.cache.client.{SessionCache, CacheMap}
import uk.gov.hmrc.play.asyncmvc.async.{Cache, AsyncPaths}
import uk.gov.hmrc.play.asyncmvc.example.config.ExampleWsHttp
import uk.gov.hmrc.play.asyncmvc.example.connectors.{Stock, StockConnector}
import uk.gov.hmrc.play.asyncmvc.example.views.html._
import uk.gov.hmrc.play.asyncmvc.model.{TaskCache, ViewCodes}
import uk.gov.hmrc.play.config.{ServicesConfig, AppName}
import uk.gov.hmrc.play.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**

Example controller where the Future and Result (based on ExampleNormalController) can be separated in order to expose a true async controller, where the
client is not blocked waiting for the server Future result!

The term async in this design is where a server-side Future is placed onto a background queue for processing, and the client is returned a HTTP polling page, where
the page auto submits back to the server to check if the server-side Future has completed. Once the Future completes, the polling action will be notified and the
result is returned to the client.


The below example controller is integrated with the play-async library ...

  * captureController - The controller is the first step and captures input from the user. The controller action is wrapped
  within asyncActionWrapper, which checks if any task is currently executing for the current session. If a task is running the user is redirected back to the poll page.

  * submitController - The submit controller is responsible for submitting the task to a background queue. The long running Future is wrapped with the asyncWrapper function.

  * updateUICallback - Success callback function is invoked once the task completes successfully.

  * updateUICallbackWithStatus - Callback invoked when a status value from the framework. The status response can be Timeout,Polling,ThrottleReached, or Error.

*/

case class InputForm(message:String, id:Long)

trait ExampleAsyncController extends Controller with AsyncMvcIntegration {

  val testUpdateSessionValue = "testSessionValue"

  lazy val stockConnector: StockConnector = ???
  lazy val httpSessionCache: SessionCache = ??? //! Note: Uncomment in order for standalone testing using the session cache!

  implicit val hc = HeaderCarrier()

  val inputUserForm = Form(
    mapping(
      "message" -> nonEmptyText,
      "id" -> longNumber
    )(InputForm.apply)(InputForm.unapply))

  /**
   * Display the capture screen...Initial start of journey.
   * ASYNC NOTE:
   * This action (asyncActionWrapper.async) will route to the poll screen if an async task is already running. The asyncActionWrapper.async action wrapper
   * is used to send the user back to the poll screen if an outstanding task is running.
   */
  def captureController = Action.async {
    implicit request =>

      // asyncActionWrapper checks if an async task is currently running. If yes, user routed back to poll page!
      asyncActionWrapper.async(updateUICallbackWithStatus) {
        flag =>
          Future.successful(Ok(uk.gov.hmrc.play.asyncmvc.example.views.html.capture(inputUserForm)))
      }
  }

  /**
   * Capture screen submits to a POST controller. Submits a Future to a background queue for processing and disconnect client from the future being executed in the background.
   * ASYNC NOTES:
   * 1) asyncActionWrapper.async will check if an async task is currently running.
   * 2) The function asyncWrapper will execute the supplied code an off-line and return the Result.
   */
  def submitController = Action.async {
    implicit request =>

      asyncActionWrapper.async(updateUICallbackWithStatus) {
        flag =>
            inputUserForm.bindFromRequest().fold(
              errors => Future.successful(BadRequest(capture(inputUserForm))),
              successForm => {

                // Async function wrapper responsible for executing below code onto a background queue.
                asyncWrapper(updateUICallback, updateUICallbackWithStatus) {
                  hc =>

                    // Invoke 3 backend I/O resources.
                    // i.e. Controller action performing an expensive operation which could incur delays in returning the response to the client.
                    for {
                      _ <- stockConnector.getStock(successForm.id)
                      _ <- stockConnector.getStock(successForm.id)
                      res <- stockConnector.getStock(successForm.id)
                    } yield(res)

                }
              }
          )
        }
    }

  /**
   *  Callback from async framework to generate the successful Result. The off-line has task completed.
   *  ASYNC NOTES:
   *  The callback can be invoked from either asyncWrapper, waitForAsyncTask or pollTask.
   */
  def updateUICallback(stock:Stock)(implicit request:Request[AnyContent]) : Future[Result] = {
    val json=stock.asInstanceOf[Stock]
    Future.successful(Ok(uk.gov.hmrc.play.asyncmvc.example.views.html.complete(json)).withSession(addSessionDataToResponseForTest("SUCCESS")))
  }

  /**
   * Callback from async framework to process 'status'.
   * ASYNC NOTES:
   * The callback can be invoked from either asyncWrapper, waitForAsyncTask or pollTask.
   */
  def updateUICallbackWithStatus(status:Int)(implicit request:Request[AnyContent]) : Future[Result] = {
    val res = status match {
      case ViewCodes.Timeout => Ok(uk.gov.hmrc.play.asyncmvc.example.views.html.timeout.apply()).withSession(addSessionDataToResponseForTest("TIMEOUT"))
      case ViewCodes.Polling => Ok(uk.gov.hmrc.play.asyncmvc.example.views.html.polling.apply())
      case ViewCodes.ThrottleReached => Ok(uk.gov.hmrc.play.asyncmvc.example.views.html.throttle_limit_reached.apply())
      case ViewCodes.Error | _ => Ok(uk.gov.hmrc.play.asyncmvc.example.views.html.error.apply()).withSession(addSessionDataToResponseForTest("ERROR"))
    }
    Future.successful(res)
  }

  // Used in tests to verify the framework can accept a Result with the session updated.
  def addSessionDataToResponseForTest(status:String)(implicit request:Request[AnyContent]) = {
    request.session.copy(data = request.session.data + (testUpdateSessionValue -> status))
  }

  /**
   * Async blocking mode. Client will block 'n' seconds for the async task to complete before routing to poll page. See AsyncMVC#waitMode.
   * ASYNC NOTE: When the AsyncMVC#waitMode waitMode is set to true, the client will be re-directed to the action 'waitForAsync' when the task is first off-lined.
   * This action will bloke for a configurable (blockingDelayTime) period of time, checking if the job completes, before returning the polling content.
   */
  def waitForTask = Action.async {
    implicit request =>
      // Wait specified time for task to complete before returning the poll page.
      waitForAsyncTask(Call("GET","/capture"), updateUICallback, updateUICallbackWithStatus)
  }

  /**
   * Invoke the library poll function to determine the result sent to the client. If no task exists then route back to start.
   * ASYNC NOTE: The poll action is invoked from the polling view.
   */
  def poll = Action.async {
    implicit request =>
      pollTask(Call("GET","/capture"), updateUICallback, updateUICallbackWithStatus)
  }

  override def sessionHttpCache = new Cache[TaskCache] {
    val keyStoreCacheName = "AsyncMVCCache"
    val keyStoreFormName  = "AsyncMVCCacheKey"

    override def put(id: String, value: TaskCache)(implicit hc:HeaderCarrier): Future[Unit] =  httpSessionCache.cache[TaskCache](keyStoreCacheName, id, keyStoreFormName, value).map(_ => Unit)
    override def get(id: String)(implicit hc:HeaderCarrier): Future[Option[TaskCache]] =  httpSessionCache.fetchAndGetEntry[TaskCache](keyStoreCacheName, id, keyStoreFormName)
  }
}

object ExampleAsyncController extends ExampleAsyncController {
  override lazy val stockConnector  = StockConnector
  override lazy val  httpSessionCache: SessionCache = SessionHttpCaching
}

object SessionHttpCaching extends SessionCache with AppName with ServicesConfig {
  override lazy val http = ExampleWsHttp
  override lazy val defaultSource = appName
  override lazy val baseUri = baseUrl("cachable.session-cache")
  override lazy val domain = getConfString("cachable.session-cache.domain", throw new Exception(s"Could not find config 'cachable.session-cache.domain'"))
}

object AsyncMap {

  val id1 = "Example"
  val id2 = "Example2"

  val applicationAsyncControllers = Seq(
    AsyncPaths(id1, "/poll"),
    AsyncPaths(id2, "some-url-for-id-example2")
  )

}
