import Axios from 'axios';
import { CheckCategory } from '@/store/types';

const dateFormat = new Intl.DateTimeFormat(
  (new Intl.NumberFormat()).resolvedOptions().locale,
  { dateStyle: "medium", timeStyle: "short" } as any
)

/**
 * The base of a check, containing all metadata but no content.
 */
export class CheckBase {
  id: number;
  creator: string;
  name: string;
  approved: boolean;
  category: CheckCategory;
  checkType: string | null;
  creationTime: string;
  updateTime: string | null;
  updateTimeMS: number;

  constructor(
    id: number,
    creator: string,
    name: string,
    approved: boolean,
    category: CheckCategory,
    checkType: string | null,
    creationTime: string,
    updateTime: string | null,
    updateTimeMS: number
  ) {
    this.id = id;
    this.creator = creator;
    this.name = name;
    this.approved = approved;
    this.category = category;
    this.checkType = checkType;
    this.creationTime = creationTime;
    this.updateTime = updateTime;
    this.updateTimeMS = updateTimeMS;
  }

  static fromJson(json: any): CheckBase {
    const creationTime = dateFormat.format(new Date(json.creationTime))
    const updateTime = json.updateTime === undefined
      ? null
      : dateFormat.format(new Date(json.updateTime));
    const updateTimeMS = json.updateTime !== undefined
      ? json.updateTime
      : json.creationTime !== undefined
      ? json.creationTime
      : 0; // default: unix epoch -> old checks without the fields won't be found

    return new CheckBase(
      json.id,
      json.creator,
      json.name,
      json.approved,
      new CheckCategory(json.category.name, json.category.id),
      json.checkType,
      creationTime,
      updateTime,
      updateTimeMS
    );
  }
}

export class IOCheckFile {
  name: string;
  content: string;

  constructor(name: string, content: string) {
    this.name = name;
    this.content = content;
  }
}

export class IOCheck {
  input: string;
  output: string | null;
  name: string;
  files: Array<IOCheckFile>;

  constructor(input: string, output: string | null, name: string, files: Array<IOCheckFile>) {
    this.input = input;
    this.name = name;
    this.output = output;
    this.files = files;
  }

  /**
   * Returns a playload that can be passed as the "payload" in a post request to update or add a check.
   */
  serializeForSending(checkClass: string): string {
    const sendableCheck: any = {}
    Object.assign(sendableCheck, this);
    sendableCheck.files = undefined

    const check: any = {
      data: sendableCheck,
      name: sendableCheck.name
    };

    return JSON.stringify({
      value: JSON.stringify(check),
      class: checkClass
    });
  }
}

/**
 * A collection of CheckBases and their content, lazily fetched.
 */
export class CheckCollection {
  private checkBases: Array<CheckBase> = []
  private checkContents: any = {}

  /**
   * Fetches the content for a single check. The promise resolves when the content
   * is saved in this collection.
   * 
   * @param check the check to fetch the content for
   */
  async fetchContent(check: CheckBase): Promise<any> {
    if (this.checkContents[check.id]) {
      return Promise.resolve(this.checkContents[check.id])
    }
    const response = await Axios.get("/checks/get-content", {
      params: {
        id: check.id
      }
    });
    const content = response.data.content;

    this.checkContents[check.id] = this.parseCheckResponse(content)

    return this.checkContents[check.id];
  }

  getCheckBases(): CheckBase[] {
    return this.checkBases
  }

  private parseCheckResponse(content: any) {
    let ioCheck: IOCheck;
    let checkClass: string;

    if (typeof content === "object") {
      if (content.class === "StaticInputOutputCheck") {
        checkClass = content.class;
        ioCheck = new IOCheck(
          content.input.join("\n"),
          content.output,
          content.name,
          content.files
        );
      } else if (content.class === "InterleavedStaticIOCheck") {
        checkClass = content.class;
        ioCheck = new IOCheck(content.text, null, content.name, content.files);
      } else {
        // eslint-disable-next-line
        console.log("Unknown check received: ");
        // eslint-disable-next-line
        console.log(content);

        return;
      }
    } else {
      const parsed = JSON.parse(content);
      checkClass = "StaticInputOutputCheck";
      ioCheck = new IOCheck(
        parsed.input.join("\n"),
        parsed.expectedOutput,
        parsed.name,
        []
      );
    }

    return { class: checkClass, check: ioCheck };
  }

  /**
   * Deletes a single check. The promise resolves if it was successfully deted.
   * 
   * @param check the check to delete
   */
  async deleteCheck(check: CheckBase): Promise<void> {
    const _ = await Axios.delete("/checks/remove/" + check.id);
    const index = this.checkBases.indexOf(check);
    if (index >= 0) {
      this.checkBases.splice(index, 1);
      delete this.checkContents[check.id];
    }
  }

  /**
   * Sets the approval status for a check. The promise resolves after the status was set.
   * 
   * @param check the check to change the approval status for
   * @param approved whether the check is approved
   */
  async setCheckApproval(check: CheckBase, approved: boolean): Promise<void> {
    const formData = new FormData();
    formData.append("id", check.id.toString());
    formData.append("approved", approved ? "true" : "false");

    const response = await Axios.post("/checks/approve", formData);
    if (response.status === 200) {
      // Actually fine, the post does not modify "check"
      // eslint-disable-next-line require-atomic-updates
      check.approved = response.data;
    }
  }

  /**
   * Fetches all checks. The promise resolves after they have been fetched.
   */
  async fetchAll(): Promise<void> {
    const response = await Axios.get("/checks/get-all");
    this.checkBases = response.data.map(CheckBase.fromJson);
    const scratchObject = ({} as any);
    this.checkBases.forEach(it => (scratchObject[it.id] = undefined));
    // This is needed as vue can not observe property addition/deletion
    // So we just build the full object and then assign to to vue (and making it reactive)
    this.checkContents = scratchObject;

    this.sort()
  }

  /**
   * Updates an IO check.
   * 
   * @param check the check data
   * @param id the check id
   * @param checkClass the class of the check
   */
  async updateIoCheck(check: IOCheck, id: number, checkClass: string): Promise<void> {
    const response = await Axios.post(`/checks/update/${id}`, {
      payload: check.serializeForSending(checkClass),
      files: check.files
    });

    this.checkContents[id] = this.parseCheckResponse(response.data.content);
  }

  private sort() {
    this.checkBases.sort((a, b) => {
      // compare by category descending (so higher ones in a nice format are first)
      if (a.category.id > b.category.id) return -1;
      if (a.category.id < b.category.id) return 1;

      // then by name
      if (a.name.toLowerCase() < b.name.toLowerCase()) return -1;
      if (a.name.toLowerCase() == b.name.toLowerCase()) return 0;

      return 1;
    })
  }
}
